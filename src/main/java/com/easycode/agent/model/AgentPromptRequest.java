package com.easycode.agent.model;

import com.easycode.llm.model.LlmMessage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One provider-neutral prompt accepted by the controlled Agent loop. */
public record AgentPromptRequest(UUID requestId, String model, List<LlmMessage> messages) {
    public AgentPromptRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must contain at least one message");
        }
    }

    public static AgentPromptRequest create(String model, List<LlmMessage> messages) {
        return new AgentPromptRequest(UUID.randomUUID(), model, messages);
    }
}
