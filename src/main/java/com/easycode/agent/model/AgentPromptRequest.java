package com.easycode.agent.model;

import com.easycode.llm.model.LlmMessage;

import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.UUID;

/** One provider-neutral prompt accepted by the controlled Agent loop. */
public record AgentPromptRequest(UUID requestId, String model, List<LlmMessage> messages,
        Duration timeout) {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    public AgentPromptRequest(UUID requestId, String model, List<LlmMessage> messages) {
        this(requestId, model, messages, DEFAULT_TIMEOUT);
    }

    public AgentPromptRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must contain at least one message");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static AgentPromptRequest create(String model, List<LlmMessage> messages) {
        return create(model, messages, DEFAULT_TIMEOUT);
    }

    public static AgentPromptRequest create(String model, List<LlmMessage> messages,
            Duration timeout) {
        return new AgentPromptRequest(UUID.randomUUID(), model, messages, timeout);
    }
}
