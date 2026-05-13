package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分片服务
 * 针对 AIOps 运维手册优化：
 * 1. 优先按 Markdown 标题层级切到叶子小节。
 * 2. 保留“原因/处理方案/步骤/命令示例”等结构完整性。
 * 3. 超长小节再按语义块（二级段落、列表、代码块）做二次切分。
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern SENTENCE_SPLIT_PATTERN = Pattern.compile("(?<=[。！？.!?])\\s+");

    @Autowired
    private DocumentChunkConfig chunkConfig;

    /**
     * 智能分片文档。
     *
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        List<LeafSection> leafSections = extractLeafSections(content);
        int globalChunkIndex = 0;

        for (LeafSection section : leafSections) {
            List<DocumentChunk> sectionChunks = chunkLeafSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    /**
     * 提取 Markdown 叶子小节。
     * 对这批 AIOps 文档来说，叶子小节通常正好对应：
     * - 一个排查步骤
     * - 一个原因及其处理方案
     * - 一个验证/预防/命令章节
     */
    private List<LeafSection> extractLeafSections(String content) {
        List<Heading> headings = parseHeadings(content);
        List<LeafSection> sections = new ArrayList<>();

        if (headings.isEmpty()) {
            sections.add(new LeafSection(null, content.trim(), 0, content.length()));
            return sections;
        }

        for (int i = 0; i < headings.size(); i++) {
            Heading current = headings.get(i);
            int end = findSectionEnd(headings, i, content.length());

            if (hasChildHeading(headings, i, end)) {
                continue;
            }

            String sectionContent = content.substring(current.start, end).trim();
            if (sectionContent.isEmpty()) {
                continue;
            }

            sections.add(new LeafSection(current.pathTitle, sectionContent, current.start, end));
        }

        if (sections.isEmpty()) {
            sections.add(new LeafSection(null, content.trim(), 0, content.length()));
        }

        return sections;
    }

    private List<Heading> parseHeadings(String content) {
        List<Heading> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);
        Deque<Heading> stack = new ArrayDeque<>();

        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();

            while (!stack.isEmpty() && stack.peekLast().level >= level) {
                stack.pollLast();
            }

            String pathTitle = buildPathTitle(stack, title);
            Heading heading = new Heading(level, title, pathTitle, matcher.start());
            headings.add(heading);
            stack.addLast(heading);
        }

        return headings;
    }

    private String buildPathTitle(Deque<Heading> stack, String currentTitle) {
        if (stack.isEmpty()) {
            return currentTitle;
        }

        StringBuilder path = new StringBuilder();
        for (Heading heading : stack) {
            if (!path.isEmpty()) {
                path.append(" / ");
            }
            path.append(heading.title);
        }
        if (!currentTitle.isEmpty()) {
            if (!path.isEmpty()) {
                path.append(" / ");
            }
            path.append(currentTitle);
        }
        return path.toString();
    }

    private int findSectionEnd(List<Heading> headings, int currentIndex, int contentLength) {
        Heading current = headings.get(currentIndex);
        for (int i = currentIndex + 1; i < headings.size(); i++) {
            if (headings.get(i).level <= current.level) {
                return headings.get(i).start;
            }
        }
        return contentLength;
    }

    private boolean hasChildHeading(List<Heading> headings, int currentIndex, int sectionEnd) {
        Heading current = headings.get(currentIndex);
        for (int i = currentIndex + 1; i < headings.size(); i++) {
            Heading next = headings.get(i);
            if (next.start >= sectionEnd) {
                return false;
            }
            if (next.level > current.level) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对叶子小节做分片。
     * 小节较短时整体保留；超长时按语义块切，并把标题路径作为稳定上下文前缀。
     */
    private List<DocumentChunk> chunkLeafSection(LeafSection section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String prefix = buildContextPrefix(section.title);
        String body = stripFirstHeading(section.content);
        String normalizedBody = body.isBlank() ? section.content.trim() : body.trim();
        String fullContent = composeChunkContent(prefix, normalizedBody);

        if (fullContent.length() <= chunkConfig.getMaxSize()) {
            chunks.add(buildChunk(fullContent, section, startChunkIndex));
            return chunks;
        }

        List<String> blocks = splitIntoSemanticBlocks(normalizedBody);
        List<String> expandedBlocks = expandOversizedBlocks(blocks, prefix);

        StringBuilder currentBody = new StringBuilder();
        int chunkIndex = startChunkIndex;

        for (String block : expandedBlocks) {
            if (block.isBlank()) {
                continue;
            }

            String candidateBody = appendBlock(currentBody.toString(), block);
            String candidate = composeChunkContent(prefix, candidateBody);

            if (currentBody.length() > 0 && candidate.length() > chunkConfig.getMaxSize()) {
                String chunkBody = currentBody.toString().trim();
                chunks.add(buildChunk(composeChunkContent(prefix, chunkBody), section, chunkIndex++));

                String overlap = getOverlapText(chunkBody);
                currentBody = new StringBuilder();
                if (!overlap.isBlank()) {
                    currentBody.append(overlap);
                }
            }

            String nextBody = appendBlock(currentBody.toString(), block);
            if (composeChunkContent(prefix, nextBody).length() > chunkConfig.getMaxSize()
                    && currentBody.length() == 0) {
                chunks.add(buildChunk(composeChunkContent(prefix, block.trim()), section, chunkIndex++));
            } else {
                currentBody = new StringBuilder(nextBody);
            }
        }

        if (currentBody.length() > 0) {
            chunks.add(buildChunk(composeChunkContent(prefix, currentBody.toString().trim()), section, chunkIndex));
        }

        return chunks;
    }

    private String buildContextPrefix(String titlePath) {
        if (titlePath == null || titlePath.isBlank()) {
            return "";
        }

        String[] parts = titlePath.split("\\s*/\\s*");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            prefix.append("#".repeat(Math.min(i + 1, 6)))
                    .append(' ')
                    .append(parts[i].trim())
                    .append('\n');
        }
        return prefix.toString().trim();
    }

    private String stripFirstHeading(String content) {
        int newline = content.indexOf('\n');
        if (newline < 0) {
            return "";
        }
        return content.substring(newline + 1).trim();
    }

    private String composeChunkContent(String prefix, String body) {
        if (prefix == null || prefix.isBlank()) {
            return body == null ? "" : body.trim();
        }
        if (body == null || body.isBlank()) {
            return prefix.trim();
        }
        return prefix.trim() + "\n\n" + body.trim();
    }

    /**
     * 按语义块切分正文：
     * - 空行分段
     * - 代码块整体保留
     * - 列表随其上下文段落一起保留
     */
    private List<String> splitIntoSemanticBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        StringBuilder current = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                if (!inCodeFence && current.length() > 0) {
                    addBlock(blocks, current);
                }
                current.append(line).append('\n');
                inCodeFence = !inCodeFence;
                if (!inCodeFence) {
                    addBlock(blocks, current);
                }
                continue;
            }

            if (inCodeFence) {
                current.append(line).append('\n');
                continue;
            }

            if (trimmed.isEmpty()) {
                addBlock(blocks, current);
                continue;
            }

            current.append(line).append('\n');
        }

        addBlock(blocks, current);
        return blocks;
    }

    private void addBlock(List<String> blocks, StringBuilder current) {
        String block = current.toString().trim();
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        current.setLength(0);
    }

    private List<String> expandOversizedBlocks(List<String> blocks, String prefix) {
        List<String> expanded = new ArrayList<>();
        int maxBodySize = Math.max(200, chunkConfig.getMaxSize() - prefix.length() - 2);

        for (String block : blocks) {
            if (block.length() <= maxBodySize) {
                expanded.add(block);
            } else {
                expanded.addAll(splitLargeBlock(block, maxBodySize));
            }
        }

        return expanded;
    }

    private List<String> splitLargeBlock(String block, int maxBodySize) {
        List<String> pieces = splitBySentences(block);
        if (pieces.size() <= 1) {
            pieces = splitByLines(block);
        }
        if (pieces.size() <= 1) {
            pieces = splitByFixedSize(block, maxBodySize);
        }

        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }

            String candidate = appendBlock(current.toString(), piece.trim());
            if (current.length() > 0 && candidate.length() > maxBodySize) {
                merged.add(current.toString().trim());
                current = new StringBuilder(piece.trim());
            } else {
                current = new StringBuilder(candidate);
            }
        }

        if (current.length() > 0) {
            merged.add(current.toString().trim());
        }

        return merged;
    }

    private List<String> splitBySentences(String text) {
        List<String> parts = new ArrayList<>();
        String[] segments = SENTENCE_SPLIT_PATTERN.split(text);
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private List<String> splitByLines(String text) {
        List<String> parts = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private List<String> splitByFixedSize(String text, int maxBodySize) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxBodySize, text.length());
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            int nextStart = end - Math.min(chunkConfig.getOverlap(), maxBodySize / 4);
            start = nextStart > start ? nextStart : end;
        }
        return parts;
    }

    private String appendBlock(String current, String block) {
        if (current == null || current.isBlank()) {
            return block.trim();
        }
        return current.trim() + "\n\n" + block.trim();
    }

    private DocumentChunk buildChunk(String content, LeafSection section, int chunkIndex) {
        DocumentChunk chunk = new DocumentChunk(
                content,
                section.startIndex,
                section.endIndex,
                chunkIndex
        );
        chunk.setTitle(section.title);
        return chunk;
    }

    /**
     * 获取重叠文本。
     * 优先在句子边界截断，避免把命令或列表切得太碎。
     */
    private String getOverlapText(String text) {
        int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
        if (overlapSize <= 0) {
            return "";
        }

        String overlap = text.substring(text.length() - overlapSize);
        int splitPoint = Math.max(
                overlap.lastIndexOf('。'),
                Math.max(
                        overlap.lastIndexOf('\n'),
                        Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
                )
        );

        if (splitPoint > overlapSize / 3) {
            return overlap.substring(splitPoint + 1).trim();
        }

        return overlap.trim();
    }

    private static class Heading {
        final int level;
        final String title;
        final String pathTitle;
        final int start;

        private Heading(int level, String title, String pathTitle, int start) {
            this.level = level;
            this.title = title;
            this.pathTitle = pathTitle;
            this.start = start;
        }
    }

    private static class LeafSection {
        final String title;
        final String content;
        final int startIndex;
        final int endIndex;

        private LeafSection(String title, String content, int startIndex, int endIndex) {
            this.title = title;
            this.content = content;
            this.startIndex = startIndex;
            this.endIndex = endIndex;
        }
    }
}
