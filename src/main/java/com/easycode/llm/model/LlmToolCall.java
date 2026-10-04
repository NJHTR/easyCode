package com.easycode.llm.model;

import java.util.Objects;
import java.util.UUID;

/** A model-proposed action; it is not an executed ToolInvocation. */
public record LlmToolCall(UUID callId, String toolName, String arguments) {
    public LlmToolCall {
        Objects.requireNonNull(callId, "callId");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (arguments == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
    }

    public static LlmToolCall create(String toolName, String arguments) {
        return new LlmToolCall(UUID.randomUUID(), toolName, arguments);
    }
}
