package com.easycode.llm.model;

import java.util.Objects;
import java.util.UUID;

/** One message sent to an LLM provider. */
public record LlmMessage(
        LlmMessageRole role, String content, UUID toolCallId, String toolName) {
    public LlmMessage(LlmMessageRole role, String content) {
        this(role, content, null, null);
    }

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (role != LlmMessageRole.TOOL && (toolCallId != null || toolName != null)) {
            throw new IllegalArgumentException("tool metadata is only valid for TOOL messages");
        }
        if (role == LlmMessageRole.TOOL
                && ((toolCallId == null) != (toolName == null))) {
            throw new IllegalArgumentException("TOOL messages require both call id and tool name");
        }
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(LlmMessageRole.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(LlmMessageRole.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(LlmMessageRole.ASSISTANT, content);
    }

    public static LlmMessage tool(String content) {
        return new LlmMessage(LlmMessageRole.TOOL, content);
    }

    public static LlmMessage tool(UUID callId, String toolName, String content) {
        return new LlmMessage(LlmMessageRole.TOOL, content, callId, toolName);
    }
}
