package com.easycode.tool.model;

import java.util.Objects;
import java.util.UUID;

/** One concrete request to invoke a named tool. */
public record ToolInvocation(UUID callId, String toolName, String input) {
    public ToolInvocation {
        Objects.requireNonNull(callId, "callId");
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
    }

    public static ToolInvocation create(String toolName, String input) {
        return new ToolInvocation(UUID.randomUUID(), toolName, input);
    }
}
