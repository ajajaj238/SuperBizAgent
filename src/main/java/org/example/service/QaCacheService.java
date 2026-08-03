package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.example.config.QaCacheProperties;
import org.example.constant.MilvusConstants;
import org.example.service.intent.UserIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * QA 答案缓存服务（答案级缓存，分离式架构）。
 *
 * 设计：
 * - Redis：一份答案 = 一个 cacheId（qa:cache:v{gen}:{cacheId}）；
 *   问题 -> cacheId 映射（qa:cache:v{gen}:q:{md5(question)}）。
 * - Milvus：每个问题一行（id=md5(question)），多行共享同一 cacheId。
 * - 读路径：精确命中 -> rerank 精排命中（候选 cosine 召回 + 主题预筛）-> 降级余弦+主题。
 * - 写路径：LLM 回答后优先归并到已有 cluster（rerank 或答案相似度校验），不重复建 Redis 答案。
 */
@Service
public class QaCacheService {

    private static final Logger logger = LoggerFactory.getLogger(QaCacheService.class);
    private static final String KEY_PREFIX = "qa:cache:v";
    private static final String QUESTION_KEY_SUFFIX = ":q:";
    private static final String GENERATION_KEY = "qa:cache:generation";
    private static final String TRAILING_PUNCTUATION = "？?!！.。，,；;：:…~～";
    private static final Pattern ALNUM_TOKEN = Pattern.compile("[a-z0-9]+");
    /**
     * 追问片段：短问题以语气词结尾（"cpu呢""内存啊""磁盘呢"），
     * 不是独立完整问题，不允许作为缓存簇的规范问题。
     */
    private static final Pattern FRAGMENT_QUESTION = Pattern.compile(
            "^[a-z0-9\\u4e00-\\u9fff]{1,10}[呢啊吧吗呀]$");
    private static final List<AskTypeRule> ASK_TYPE_RULES = List.of(
            new AskTypeRule("cause", List.of(
                    Pattern.compile("是什么原因"),
                    Pattern.compile("什么原因"),
                    Pattern.compile("为什么"),
                    Pattern.compile("为啥"),
                    Pattern.compile("的原因"),
                    Pattern.compile("因何"),
                    Pattern.compile("是咋回事"))),
            new AskTypeRule("definition", List.of(
                    Pattern.compile("是什么(?!原因)"),
                    Pattern.compile("什么是"),
                    Pattern.compile("是啥"),
                    Pattern.compile("有哪些"),
                    Pattern.compile("有哪几种"),
                    Pattern.compile("包括哪些"))),
            new AskTypeRule("solution", List.of(
                    Pattern.compile("怎么解决"),
                    Pattern.compile("如何解决"),
                    Pattern.compile("怎么办"),
                    Pattern.compile("怎么处理"),
                    Pattern.compile("如何处理"),
                    Pattern.compile("怎么修复"),
                    Pattern.compile("如何修复"),
                    Pattern.compile("怎么应对"),
                    Pattern.compile("如何应对"))),
            new AskTypeRule("troubleshoot", List.of(
                    Pattern.compile("怎么排查"),
                    Pattern.compile("如何排查"),
                    Pattern.compile("怎么定位"),
                    Pattern.compile("如何定位"),
                    Pattern.compile("怎么分析"),
                    Pattern.compile("如何分析"),
                    Pattern.compile("怎么查"),
                    Pattern.compile("如何查"),
                    Pattern.compile("怎么判断"),
                    Pattern.compile("如何判断"))),
            new AskTypeRule("prevent", List.of(
                    Pattern.compile("怎么避免"),
                    Pattern.compile("如何避免"),
                    Pattern.compile("怎么预防"),
                    Pattern.compile("如何预防"),
                    Pattern.compile("怎么防止"),
                    Pattern.compile("如何防止"),
                    Pattern.compile("怎么规避"),
                    Pattern.compile("如何规避")))
    );
    private static final Set<String> DYNAMIC_INTENTS = Set.of(
            UserIntent.TIME_QUERY.name(),
            UserIntent.METRICS_QUERY.name(),
            UserIntent.LOG_QUERY.name(),
            UserIntent.ALERT_DIAGNOSIS.name());

    private final QaCacheProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final RerankService rerankService;
    private final MeterRegistry meterRegistry;

    private final List<Pattern> dynamicPatterns;
    private final ExecutorService cacheExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(256),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final Counter hitExactCounter;
    private final Counter hitSemanticCounter;
    private final Counter missCounter;
    private final Counter savedCounter;
    private final Counter mergeCounter;
    private final Counter skippedDynamicCounter;
    private final Counter skippedIntentCounter;
    private final Counter skippedInvalidCounter;
    private final Counter invalidatedCounter;
    private final Counter errorCounter;
    private final Counter rerankErrorCounter;
    private final Timer lookupTimer;
    private final Timer rerankTimer;

    public QaCacheService(QaCacheProperties properties,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          MilvusServiceClient milvusClient,
                          VectorEmbeddingService embeddingService,
                          VectorSearchService vectorSearchService,
                          RerankService rerankService,
                          MeterRegistry meterRegistry) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.rerankService = rerankService;
        this.meterRegistry = meterRegistry;

        this.dynamicPatterns = new ArrayList<>();
        if (properties.getDynamicPatterns() != null) {
            for (String pattern : properties.getDynamicPatterns()) {
                try {
                    this.dynamicPatterns.add(Pattern.compile(pattern));
                } catch (Exception e) {
                    logger.warn("QA缓存动态问题正则无效，已忽略: {} -> {}", pattern, e.getMessage());
                }
            }
        }

        this.hitExactCounter = meterRegistry.counter("qa.cache.hit", "type", "exact");
        this.hitSemanticCounter = meterRegistry.counter("qa.cache.hit", "type", "semantic");
        this.missCounter = meterRegistry.counter("qa.cache.miss");
        this.savedCounter = meterRegistry.counter("qa.cache.saved");
        this.mergeCounter = meterRegistry.counter("qa.cache.merge");
        this.skippedDynamicCounter = meterRegistry.counter("qa.cache.skipped", "reason", "dynamic");
        this.skippedIntentCounter = meterRegistry.counter("qa.cache.skipped", "reason", "intent");
        this.skippedInvalidCounter = meterRegistry.counter("qa.cache.skipped", "reason", "invalid");
        this.invalidatedCounter = meterRegistry.counter("qa.cache.invalidated");
        this.errorCounter = meterRegistry.counter("qa.cache.error");
        this.rerankErrorCounter = meterRegistry.counter("qa.cache.rerank.error");
        this.lookupTimer = meterRegistry.timer("qa.cache.lookup");
        this.rerankTimer = meterRegistry.timer("qa.cache.rerank");
    }

    /**
     * 查询缓存。命中返回缓存答案，未命中或不可缓存返回 null（走正常 LLM 流程）。
     */
    public CacheLookupResult lookup(String rawQuestion) {
        if (!properties.isEnabled() || rawQuestion == null) {
            return null;
        }
        long start = System.nanoTime();
        try {
            String question = normalize(rawQuestion);
            if (!passesLengthGate(question)) {
                return null;
            }
            if (isDynamicQuestion(question)) {
                skippedDynamicCounter.increment();
                return null;
            }

            String questionMd5 = md5(question);

            // 1) 精确命中：问题 -> cacheId -> Redis 答案（不调 embedding / rerank）
            String cacheId = redisTemplate.opsForValue().get(questionKey(questionMd5));
            if (cacheId != null) {
                String value = redisTemplate.opsForValue().get(answerKey(cacheId));
                if (value != null) {
                    CachedAnswer cached = parseAnswer(value);
                    if (cached != null) {
                        hitExactCounter.increment();
                        logger.info("QA缓存精确命中: question='{}', cacheId={}", question, cacheId);
                        return new CacheLookupResult(cached.answer(), cached.question(), "exact");
                    }
                }
            }

            // 2) 语义路径：embedding 召回 -> 主题预筛 -> rerank 精排
            List<Float> queryVector = embeddingService.generateQueryVector(question);
            if (properties.isIntentCheckEnabled() && isDynamicIntent(queryVector)) {
                skippedIntentCounter.increment();
                return null;
            }

            int topK = Math.max(3, properties.getRerank().getTopN());
            List<CacheCandidate> candidates = searchCachedQuestions(queryVector, topK);
            List<CacheCandidate> filtered = filterBySubject(question, candidates);

            if (properties.getRerank().isEnabled() && !filtered.isEmpty()) {
                try {
                    long rerankStart = System.nanoTime();
                    List<RerankService.RerankScore> scores = rerankService.rerank(
                            question, filtered.stream().map(CacheCandidate::question).collect(Collectors.toList()));
                    rerankTimer.record(System.nanoTime() - rerankStart, TimeUnit.NANOSECONDS);

                    for (RerankService.RerankScore score : scores) {
                        if (score.index() < 0 || score.index() >= filtered.size()) {
                            continue;
                        }
                        if (score.score() < properties.getRerank().getThreshold()) {
                            break;
                        }
                        CacheCandidate hit = filtered.get(score.index());
                        String value = redisTemplate.opsForValue().get(answerKey(hit.cacheId()));
                        if (value == null) {
                            deleteDeadRowAsync(hit);
                            continue;
                        }
                        CachedAnswer cached = parseAnswer(value);
                        if (cached != null && isClusterAskTypeCompatible(question, cached)) {
                            hitSemanticCounter.increment();
                            logger.info("QA缓存rerank命中: question='{}', matched='{}', score={}",
                                    question, hit.question(), String.format("%.4f", score.score()));
                            bindQuestionAsync(question, questionMd5, hit.cacheId(), queryVector);
                            return new CacheLookupResult(cached.answer(), cached.question(), "semantic");
                        }
                    }
                } catch (Exception e) {
                    rerankErrorCounter.increment();
                    logger.warn("rerank 失败，读侧降级为余弦+主题校验: {}", e.getMessage());
                    // 降级到下面的余弦路径
                    CacheLookupResult fallback = lookupByCosine(question, questionMd5, filtered, queryVector);
                    if (fallback != null) {
                        return fallback;
                    }
                }
                // rerank 调用成功但未达阈值：以 rerank 判定为准，不命中
                missCounter.increment();
                return null;
            }

            // 3) rerank 未启用：余弦 + 主题校验
            CacheLookupResult fallback = lookupByCosine(question, questionMd5, filtered, queryVector);
            if (fallback != null) {
                return fallback;
            }

            missCounter.increment();
            return null;
        } catch (Exception e) {
            errorCounter.increment();
            logger.warn("QA缓存查询失败，降级为正常 LLM 流程: {}", e.getMessage());
            return null;
        } finally {
            lookupTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 余弦 + 主题一致性兜底命中（rerank 未启用或调用失败时使用）
     */
    private CacheLookupResult lookupByCosine(String question, String questionMd5,
                                             List<CacheCandidate> filtered, List<Float> queryVector) {
        for (CacheCandidate candidate : filtered) {
            if (candidate.score() < properties.getSimilarityThreshold()) {
                break;
            }
            String value = redisTemplate.opsForValue().get(answerKey(candidate.cacheId()));
            if (value != null) {
                CachedAnswer cached = parseAnswer(value);
                if (cached != null && isClusterAskTypeCompatible(question, cached)) {
                    hitSemanticCounter.increment();
                    logger.info("QA缓存语义命中(余弦): question='{}', matched='{}', score={}",
                            question, candidate.question(), String.format("%.4f", candidate.score()));
                    bindQuestionAsync(question, questionMd5, candidate.cacheId(), queryVector);
                    return new CacheLookupResult(cached.answer(), cached.question(), "semantic");
                }
            } else {
                deleteDeadRowAsync(candidate);
            }
        }
        return null;
    }

    /**
     * 首次回答后落缓存。优先归并到已有答案 cluster，不重复建 Redis 答案。
     * 追问（如"cpu呢"）会携带改写后的完整问题 canonicalQuestion，
     * 以改写后的语句作为规范问题建簇，并把原始追问绑定到同一 cluster。
     */
    public void trySave(String rawQuestion, String canonicalQuestion, String answer, UserIntent intent) {
        if (!properties.isEnabled()) {
            return;
        }
        if (intent != UserIntent.KNOWLEDGE_QA) {
            skippedIntentCounter.increment();
            return;
        }

        String canonicalInitial = normalize(canonicalQuestion);
        // 规范问题不可用（改写失败/规则兜底模板）或本身是追问片段（如"cpu呢"）时，
        // 不落缓存：追问片段只能绑定到改写后问题的缓存簇，不能成为独立缓存
        if (canonicalInitial.isBlank()
                || canonicalInitial.startsWith("基于上一轮问题")
                || isFragmentQuestion(canonicalInitial)) {
            skippedInvalidCounter.increment();
            return;
        }
        final String canonical = canonicalInitial;
        if (!passesLengthGate(canonical) || isDynamicQuestion(canonical)) {
            skippedDynamicCounter.increment();
            return;
        }
        if (answer == null || answer.isBlank() || answer.trim().length() < 20
                || answer.length() > properties.getMaxAnswerLength()) {
            skippedInvalidCounter.increment();
            return;
        }

        final String raw = normalize(rawQuestion);
        if (properties.isAsyncSave()) {
            cacheExecutor.execute(() -> doSave(raw, canonical, answer, intent));
        } else {
            doSave(raw, canonical, answer, intent);
        }
    }

    private void doSave(String rawQuestion, String canonicalQuestion, String answer, UserIntent intent) {
        try {
            String canonicalMd5 = md5(canonicalQuestion);
            List<Float> canonicalVector = embeddingService.generateEmbedding(canonicalQuestion);

            // 幂等守卫：规范问题已缓存且答案有效时直接复用，避免重复追问反复建簇
            String existingCacheId = redisTemplate.opsForValue().get(questionKey(canonicalMd5));
            if (existingCacheId != null && hasValidAnswer(existingCacheId)) {
                logger.info("QA缓存规范问题已存在，直接复用: canonical='{}', cacheId={}",
                        canonicalQuestion, existingCacheId);
                if (!rawQuestion.equals(canonicalQuestion) && !isFragmentQuestion(rawQuestion)) {
                    bindQuestion(rawQuestion, null, existingCacheId);
                }
                return;
            }

            String mergeCacheId = findMergeTarget(canonicalQuestion, answer, canonicalVector);
            if (mergeCacheId != null) {
                bindQuestion(canonicalQuestion, canonicalVector, mergeCacheId);
                if (!rawQuestion.equals(canonicalQuestion) && !isFragmentQuestion(rawQuestion)) {
                    bindQuestion(rawQuestion, null, mergeCacheId);
                }
                mergeCounter.increment();
                logger.info("QA缓存问题归并到已有答案: canonical='{}', raw='{}', cacheId={}",
                        canonicalQuestion, rawQuestion, mergeCacheId);
                return;
            }

            String cacheId = canonicalMd5;
            CachedAnswer cached = new CachedAnswer(answer, canonicalQuestion, System.currentTimeMillis(), intent.name(), 1);
            String json = objectMapper.writeValueAsString(cached);
            redisTemplate.opsForValue().set(answerKey(cacheId), json, properties.getRedisTtl());
            savedCounter.increment();
            bindQuestion(canonicalQuestion, canonicalVector, cacheId);
            if (!rawQuestion.equals(canonicalQuestion) && !isFragmentQuestion(rawQuestion)) {
                bindQuestion(rawQuestion, null, cacheId);
            }
            logger.info("QA缓存新建答案: canonical='{}', raw='{}', cacheId={}",
                    canonicalQuestion, rawQuestion, cacheId);
        } catch (Exception e) {
            errorCounter.increment();
            logger.warn("QA缓存写入失败: {}", e.getMessage());
        }
    }

    private void bindQuestion(String question, List<Float> queryVector, String cacheId) {
        String questionMd5 = md5(question);
        redisTemplate.opsForValue().set(questionKey(questionMd5), cacheId, properties.getRedisTtl());
        List<Float> vector = queryVector != null ? queryVector : embeddingService.generateEmbedding(question);
        insertToMilvus(questionMd5, question, vector, cacheId);
    }

    /**
     * 判断新问题能否归并到已有答案 cluster。
     * 归并条件：rerank 判定命中（或余弦≥阈值），或新 LLM 答案与缓存答案语义相似。
     */
    private String findMergeTarget(String question, String answer, List<Float> queryVector) {
        int topK = Math.max(3, properties.getRerank().getTopN());
        List<CacheCandidate> candidates = searchCachedQuestions(queryVector, topK);
        List<CacheCandidate> filtered = filterBySubject(question, candidates);
        if (filtered.isEmpty()) {
            return null;
        }

        String targetCacheId = null;

        // 1) rerank 判定
        if (properties.getRerank().isEnabled()) {
            try {
                List<RerankService.RerankScore> scores = rerankService.rerank(
                        question, filtered.stream().map(CacheCandidate::question).collect(Collectors.toList()));
                for (RerankService.RerankScore score : scores) {
                    if (score.index() < 0 || score.index() >= filtered.size()) {
                        continue;
                    }
                    if (score.score() < properties.getRerank().getThreshold()) {
                        break;
                    }
                    CacheCandidate candidate = filtered.get(score.index());
                    if (isClusterCompatible(question, candidate.cacheId())) {
                        targetCacheId = candidate.cacheId();
                        break;
                    }
                    if (!hasValidAnswer(candidate.cacheId())) {
                        deleteDeadRowAsync(candidate);
                    }
                }
            } catch (Exception e) {
                rerankErrorCounter.increment();
                logger.warn("rerank 失败，写侧降级为余弦归并: {}", e.getMessage());
                for (CacheCandidate candidate : filtered) {
                    if (candidate.score() < properties.getSimilarityThreshold()) {
                        break;
                    }
                    if (isClusterCompatible(question, candidate.cacheId())) {
                        targetCacheId = candidate.cacheId();
                        break;
                    }
                    if (!hasValidAnswer(candidate.cacheId())) {
                        deleteDeadRowAsync(candidate);
                    }
                }
            }
        } else {
            for (CacheCandidate candidate : filtered) {
                if (candidate.score() < properties.getSimilarityThreshold()) {
                    break;
                }
                if (isClusterCompatible(question, candidate.cacheId())) {
                    targetCacheId = candidate.cacheId();
                    break;
                }
                if (!hasValidAnswer(candidate.cacheId())) {
                    deleteDeadRowAsync(candidate);
                }
            }
        }

        // 合并目标必须是"存在有效答案且诉求类型一致"的 cluster，否则降级为新建
        if (targetCacheId != null && !isClusterCompatible(question, targetCacheId)) {
            logger.info("合并目标无有效答案或诉求不一致，放弃归并: question='{}', cacheId={}",
                    question, targetCacheId);
            targetCacheId = null;
        }

        // 2) 答案相似度校验（防归并错误答案）
        if (targetCacheId == null && properties.isMergeAnswerCheckEnabled()) {
            for (CacheCandidate candidate : filtered) {
                if (!isClusterCompatible(question, candidate.cacheId())) {
                    if (!hasValidAnswer(candidate.cacheId())) {
                        deleteDeadRowAsync(candidate);
                    }
                    continue;
                }
                String cachedJson = redisTemplate.opsForValue().get(answerKey(candidate.cacheId()));
                if (cachedJson != null) {
                    CachedAnswer cached = parseAnswer(cachedJson);
                    if (cached != null && cached.answer() != null && !cached.answer().isBlank()) {
                        List<Float> newAnswerVec = embeddingService.generateEmbedding(answer);
                        List<Float> cachedAnswerVec = embeddingService.generateEmbedding(cached.answer());
                        float sim = embeddingService.calculateCosineSimilarity(newAnswerVec, cachedAnswerVec);
                        logger.info("答案相似度校验: question='{}', sim={}", question, String.format("%.4f", sim));
                        if (sim >= properties.getAnswerSimilarityThreshold()) {
                            targetCacheId = candidate.cacheId();
                            break;
                        }
                    }
                }
            }
        }
        return targetCacheId;
    }

    /**
     * 异步删除失效的缓存行（其 Redis 答案已不存在，通常是缓存失效后的残留），
     * 避免死行与有效行同文本时遮蔽命中。
     */
    private void deleteDeadRowAsync(CacheCandidate candidate) {
        if (candidate == null || candidate.id() == null) {
            return;
        }
        cacheExecutor.execute(() -> {
            try {
                milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                        .build());
                R<MutationResult> response = milvusClient.delete(DeleteParam.newBuilder()
                        .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                        .withExpr("id == \"" + candidate.id() + "\"")
                        .build());
                if (response.getStatus() == 0) {
                    logger.info("已清理失效缓存行: question='{}', id={}", candidate.question(), candidate.id());
                }
            } catch (Exception e) {
                logger.debug("清理失效缓存行失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 目标 cluster 必须存在有效答案，且 canonical 问题与当前问题的诉求类型一致
     * （防止"原因"答案被"解决"类问题复用，也防止失效后归并到无答案的死 cluster）。
     */
    private boolean isClusterCompatible(String question, String cacheId) {
        String value = redisTemplate.opsForValue().get(answerKey(cacheId));
        if (value == null) {
            return false;
        }
        CachedAnswer cached = parseAnswer(value);
        return cached != null && isClusterAskTypeCompatible(question, cached);
    }

    private boolean isClusterAskTypeCompatible(String question, CachedAnswer cached) {
        if (!properties.isAskTypeCheckEnabled()) {
            return true;
        }
        return isAskTypeConsistent(question, cached.question());
    }

    /**
     * 语义命中后异步把本次问题写入 Milvus 并绑定同一 cacheId，
     * 下次相同问题可直接精确命中。
     */
    private void bindQuestionAsync(String question, String questionMd5, String cacheId, List<Float> queryVector) {
        cacheExecutor.execute(() -> {
            try {
                redisTemplate.opsForValue().set(questionKey(questionMd5), cacheId, properties.getRedisTtl());
                insertToMilvus(questionMd5, question, queryVector, cacheId);
                logger.info("QA缓存新问题绑定已有答案: question='{}', cacheId={}", question, cacheId);
            } catch (Exception e) {
                logger.warn("QA缓存问题绑定失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 文档变更时使缓存失效：切换 Redis 代次，旧答案随 TTL 过期；
     * Milvus 中旧问题记录即使被语义命中，也会因新代次 Redis 键不存在而降级。
     */
    public void invalidateAll() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Long generation = redisTemplate.opsForValue().increment(GENERATION_KEY);
            // 首次失效：代次键缺失时 INCR 为 1，与缺失默认代次(1)相同，需再自增一次确保代次真正变化
            if (generation != null && generation == 1L) {
                generation = redisTemplate.opsForValue().increment(GENERATION_KEY);
            }
            invalidatedCounter.increment();
            logger.info("QA答案缓存已失效，切换至代次: {}", generation);
        } catch (Exception e) {
            logger.warn("QA答案缓存失效失败: {}", e.getMessage());
        }
    }

    /**
     * 返回当前缓存统计（Prometheus 计数器快照），供管理接口/测试使用。
     */
    public Map<String, Double> stats() {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Meter meter : meterRegistry.getMeters()) {
            String name = meter.getId().getName();
            if (name == null || !name.startsWith("qa.cache.")) {
                continue;
            }
            String tags = meter.getId().getTags().stream()
                    .map(tag -> tag.getKey() + "=" + tag.getValue())
                    .collect(Collectors.joining(","));
            String label = tags.isEmpty() ? name : name + "{" + tags + "}";
            if (meter instanceof Counter counter) {
                result.put(label, counter.count());
            } else if (meter instanceof Timer timer) {
                result.put(label + "_count", (double) timer.count());
                result.put(label + "_total_seconds", timer.totalTime(TimeUnit.SECONDS));
            }
        }
        return result;
    }

    /**
     * 规范化问题文本：去空白、去尾部标点、拉丁字母小写。
     */
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim()
                .replaceAll("[\\s\u3000]+", " ")
                .toLowerCase(Locale.ROOT);
        while (!text.isEmpty() && TRAILING_PUNCTUATION.indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1);
        }
        return text.trim();
    }

    /**
     * 判断是否为动态/时效问题（时间、实时数据等），此类问题不查缓存也不落缓存。
     */
    public boolean isDynamicQuestion(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return false;
        }
        if (properties.getDynamicKeywords() != null) {
            for (String keyword : properties.getDynamicKeywords()) {
                if (normalizedQuestion.contains(keyword)) {
                    return true;
                }
            }
        }
        for (Pattern pattern : dynamicPatterns) {
            if (pattern.matcher(normalizedQuestion).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isFragmentQuestion(String question) {
        return question != null && FRAGMENT_QUESTION.matcher(question).find();
    }

    /**
     * 主题一致性校验：查询与候选问题必须共享至少一个领域主题词
     * （如 CPU/内存/磁盘/服务），防止"内存飙高怎么办"命中"CPU飙高怎么办"。
     * 任意一侧无法提取主题词时放行，交由阈值把关。
     */
    public boolean isSubjectConsistent(String query, String candidate) {
        Set<String> querySubjects = extractSubjects(query);
        Set<String> candidateSubjects = extractSubjects(candidate);
        if (querySubjects.isEmpty() || candidateSubjects.isEmpty()) {
            return true;
        }
        querySubjects.retainAll(candidateSubjects);
        return !querySubjects.isEmpty();
    }

    private Set<String> extractSubjects(String text) {
        Set<String> subjects = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return subjects;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = ALNUM_TOKEN.matcher(lower);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 && !token.matches("\\d+")) {
                subjects.add(token);
            }
        }
        if (properties.getSubjectWords() != null) {
            for (String word : properties.getSubjectWords()) {
                if (word != null && !word.isBlank() && lower.contains(word.toLowerCase(Locale.ROOT))) {
                    subjects.add(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        return subjects;
    }

    private boolean passesLengthGate(String question) {
        if (question.isBlank()) {
            return false;
        }
        return question.length() >= properties.getMinQuestionLength()
                && question.length() <= properties.getMaxQuestionLength();
    }

    private boolean isDynamicIntent(List<Float> queryVector) {
        try {
            List<VectorSearchService.IntentSearchResult> results =
                    vectorSearchService.searchIntentExamples("", 1, queryVector);
            if (results == null || results.isEmpty()) {
                return false;
            }
            VectorSearchService.IntentSearchResult top = results.get(0);
            if (DYNAMIC_INTENTS.contains(top.getIntent())
                    && top.getScore() >= properties.getIntentThreshold()) {
                logger.debug("QA缓存读侧意图闸门拦截: intent={}, score={}", top.getIntent(), top.getScore());
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.warn("QA缓存意图闸门检查失败，放行: {}", e.getMessage());
            return false;
        }
    }

    private List<CacheCandidate> filterBySubject(String question, List<CacheCandidate> candidates) {
        if ((!properties.isSubjectCheckEnabled() && !properties.isAskTypeCheckEnabled())
                || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        // 按问题文本去重，优先保留有有效答案的行（历史失效行可能残留）
        Map<String, CacheCandidate> byText = new LinkedHashMap<>();
        for (CacheCandidate candidate : candidates) {
            if (properties.isSubjectCheckEnabled()
                    && !isSubjectConsistent(question, candidate.question())) {
                continue;
            }
            if (properties.isAskTypeCheckEnabled()
                    && !isAskTypeConsistent(question, candidate.question())) {
                continue;
            }
            String key = candidate.question() == null ? "" : candidate.question();
            CacheCandidate existing = byText.get(key);
            if (existing == null) {
                byText.put(key, candidate);
            } else if (!hasValidAnswer(existing.cacheId()) && hasValidAnswer(candidate.cacheId())) {
                byText.put(key, candidate);
            }
        }
        return new ArrayList<>(byText.values());
    }

    private boolean hasValidAnswer(String cacheId) {
        String value = redisTemplate.opsForValue().get(answerKey(cacheId));
        return value != null && parseAnswer(value) != null;
    }

    /**
     * 诉求类型一致性校验：
     * "cpu太高是什么原因"（cause）与 "cpu太高怎么解决"（solution）同主题但诉求不同，
     * 不得共用缓存答案。任一侧无法识别诉求类型时放行。
     */
    public boolean isAskTypeConsistent(String query, String candidate) {
        String queryType = extractAskType(query);
        String candidateType = extractAskType(candidate);
        if (queryType == null || candidateType == null) {
            return true;
        }
        return queryType.equals(candidateType);
    }

    private String extractAskType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (AskTypeRule rule : ASK_TYPE_RULES) {
            for (Pattern pattern : rule.patterns()) {
                if (pattern.matcher(normalized).find()) {
                    return rule.type();
                }
            }
        }
        return null;
    }

    private record AskTypeRule(String type, List<Pattern> patterns) {
    }

    private List<CacheCandidate> searchCachedQuestions(List<Float> queryVector, int topK) {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .build());
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 qa_cache collection 失败: " + loadResponse.getMessage());
        }

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .withVectorFieldName("embedding")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("id", "question", "cache_id"))
                .withParams("{\"nprobe\":10}")
                .build();

        R<SearchResults> response = milvusClient.search(searchParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("qa_cache 向量搜索失败: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<CacheCandidate> results = new ArrayList<>();
        int rowCount = wrapper.getRowRecords(0).size();
        for (int i = 0; i < rowCount; i++) {
            float score = wrapper.getIDScore(0).get(i).getScore();
            if (score < properties.getRecallThreshold()) {
                continue;
            }
            String id = (String) wrapper.getIDScore(0).get(i).get("id");
            String question = (String) wrapper.getFieldData("question", 0).get(i);
            Object cacheIdObj = wrapper.getFieldData("cache_id", 0).get(i);
            String cacheId = cacheIdObj == null ? null : cacheIdObj.toString();
            if (cacheId == null || cacheId.isBlank()) {
                continue;
            }
            results.add(new CacheCandidate(id, question, cacheId, score));
        }
        return results;
    }

    private void insertToMilvus(String id, String question, List<Float> vector, String cacheId) {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .build());
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 qa_cache collection 失败: " + loadResponse.getMessage());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("question", Collections.singletonList(question)));
        fields.add(new InsertParam.Field("cache_id", Collections.singletonList(cacheId)));
        fields.add(new InsertParam.Field("hit_count", Collections.singletonList(1L)));
        fields.add(new InsertParam.Field("created_at", Collections.singletonList(System.currentTimeMillis())));
        fields.add(new InsertParam.Field("embedding", Collections.singletonList(vector)));

        R<MutationResult> response = milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.QA_CACHE_COLLECTION_NAME)
                .withFields(fields)
                .build());
        if (response.getStatus() != 0) {
            String message = response.getMessage() == null ? "" : response.getMessage();
            if (message.toLowerCase().contains("already exist") || message.toLowerCase().contains("duplicated")) {
                logger.debug("QA缓存问题已存在，跳过写入: {}", question);
                return;
            }
            throw new RuntimeException("写入 qa_cache 失败: " + message);
        }
        logger.info("QA缓存问题已写入 Milvus: question='{}', cacheId={}", question, cacheId);
    }

    private String answerKey(String cacheId) {
        return KEY_PREFIX + generation() + ":" + cacheId;
    }

    private String questionKey(String questionMd5) {
        return KEY_PREFIX + generation() + QUESTION_KEY_SUFFIX + questionMd5;
    }

    private long generation() {
        String value = redisTemplate.opsForValue().get(GENERATION_KEY);
        if (value == null || value.isBlank()) {
            return 1L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    private CachedAnswer parseAnswer(String json) {
        try {
            return objectMapper.readValue(json, CachedAnswer.class);
        } catch (Exception e) {
            logger.warn("解析缓存答案失败: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        cacheExecutor.shutdown();
    }

    public record CachedAnswer(String answer, String question, long createdAt, String intent, Integer questionCount) {
    }

    public record CacheLookupResult(String answer, String matchedQuestion, String matchType) {
    }

    private record CacheCandidate(String id, String question, String cacheId, float score) {
    }
}
