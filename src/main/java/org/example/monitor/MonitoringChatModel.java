package org.example.monitor;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 包装底层 ChatModel，自动记录每次调用的 token usage。
 */
public class MonitoringChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageRecorder tokenUsageRecorder;
    private final String configuredModelName;

    public MonitoringChatModel(ChatModel delegate, TokenUsageRecorder tokenUsageRecorder, String configuredModelName) {
        this.delegate = delegate;
        this.tokenUsageRecorder = tokenUsageRecorder;
        this.configuredModelName = configuredModelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        tokenUsageRecorder.recordChatUsage(TokenUsageContext.currentTraceId(), prompt, response, configuredModelName);
        return response;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String traceId = TokenUsageContext.currentTraceId();
        AtomicInteger lastPromptTokens = new AtomicInteger();
        AtomicInteger lastCompletionTokens = new AtomicInteger();
        AtomicInteger lastTotalTokens = new AtomicInteger();
        AtomicReference<String> modelNameRef = new AtomicReference<>(configuredModelName);
        StringBuilder responseTextBuilder = new StringBuilder();

        return delegate.stream(prompt)
                .doOnNext(response -> {
                    modelNameRef.set(resolveModelName(response, configuredModelName));
                    String chunk = extractAnswerText(response);
                    if (chunk != null && !chunk.isEmpty()) {
                        responseTextBuilder.append(chunk);
                    }

                    ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
                    Usage usage = metadata == null ? null : metadata.getUsage();
                    if (usage == null) {
                        return;
                    }

                    int promptTokens = safeValue(usage.getPromptTokens());
                    int completionTokens = safeValue(usage.getCompletionTokens());
                    int totalTokens = safeValue(usage.getTotalTokens());

                    int deltaPrompt = Math.max(promptTokens - lastPromptTokens.getAndSet(promptTokens), 0);
                    int deltaCompletion = Math.max(completionTokens - lastCompletionTokens.getAndSet(completionTokens), 0);
                    int deltaTotal = Math.max(totalTokens - lastTotalTokens.getAndSet(totalTokens), 0);

                    if (deltaPrompt > 0 || deltaCompletion > 0 || deltaTotal > 0) {
                        tokenUsageRecorder.recordChatUsage(
                                traceId,
                                resolveModelName(response, configuredModelName),
                                deltaPrompt,
                                deltaCompletion,
                                deltaTotal,
                                "actual"
                        );
                    }
                })
                .doOnComplete(() -> {
                    if (lastTotalTokens.get() == 0) {
                        int estimatedPromptTokens = TokenEstimator.estimatePromptTokens(prompt);
                        tokenUsageRecorder.recordChatUsage(
                                traceId,
                                configuredModelName,
                                estimatedPromptTokens,
                                0,
                                estimatedPromptTokens,
                                "estimated"
                        );
                    }
                    tokenUsageRecorder.recordChatExchange(
                            traceId,
                            modelNameRef.get(),
                            prompt == null ? "" : prompt.getContents(),
                            responseTextBuilder.toString(),
                            "stream"
                    );
                });
    }

    private String resolveModelName(ChatResponse response, String fallback) {
        ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
        if (metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()) {
            return metadata.getModel();
        }
        return fallback;
    }

    private int safeValue(Integer value) {
        return value == null ? 0 : value;
    }

    private String extractAnswerText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        return response.getResult().getOutput().getText();
    }
}
