package com.easycode.llm.model;

import java.util.List;

/** Provider-neutral model output; tool calls are data, not execution. */
public record LlmResponse(String content, List<LlmToolCall> toolCalls) {
    public LlmResponse {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (toolCalls.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("toolCalls cannot contain null");
        }
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "response must contain content or at least one tool call");
        }
    }

    public static LlmResponse text(String content) {
        return new LlmResponse(content, List.of());
    }

    public static LlmResponse withToolCalls(String content, List<LlmToolCall> toolCalls) {
        return new LlmResponse(content, toolCalls);
    }
}
