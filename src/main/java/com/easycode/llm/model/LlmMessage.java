package com.easycode.llm.model;

import java.util.Objects;
import java.util.UUID;
import java.util.List;

/** One message sent to an LLM provider. */
public record LlmMessage(
        LlmMessageRole role,
        String content,
        UUID toolCallId,
        String toolName,
        List<LlmToolCall> toolCalls) {
    public LlmMessage(LlmMessageRole role, String content) {
        this(role, content, null, null, List.of());
    }

    public LlmMessage(
            LlmMessageRole role, String content, UUID toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, List.of());
    }

    public LlmMessage {
        Objects.requireNonNull(role, "role");
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (toolCalls.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("toolCalls cannot contain null");
        }
        if (role != LlmMessageRole.TOOL && (toolCallId != null || toolName != null)) {
            throw new IllegalArgumentException("tool metadata is only valid for TOOL messages");
        }
        if (role != LlmMessageRole.ASSISTANT && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("tool calls are only valid for ASSISTANT messages");
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

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(LlmMessageRole.ASSISTANT, content, null, null, toolCalls);
    }

    public static LlmMessage tool(String content) {
        return new LlmMessage(LlmMessageRole.TOOL, content);
    }

    public static LlmMessage tool(UUID callId, String toolName, String content) {
        return new LlmMessage(LlmMessageRole.TOOL, content, callId, toolName);
    }
}
