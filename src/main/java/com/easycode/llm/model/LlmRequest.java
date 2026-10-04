package com.easycode.llm.model;

import java.util.List;
import java.util.Objects;

/** One provider-neutral LLM request. */
public record LlmRequest(String model, List<LlmMessage> messages) {
    public LlmRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must contain at least one message");
        }
    }

    public static LlmRequest of(String model, List<LlmMessage> messages) {
        return new LlmRequest(model, messages);
    }
}
