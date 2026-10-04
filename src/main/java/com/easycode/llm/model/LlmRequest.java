package com.easycode.llm.model;

import com.easycode.tool.model.ToolDefinition;

import java.util.List;
import java.util.Objects;

/** One provider-neutral LLM request. */
public record LlmRequest(
        String model,
        List<LlmMessage> messages,
        List<ToolDefinition> tools) {

    public LlmRequest(String model, List<LlmMessage> messages) {
        this(model, messages, List.of());
    }

    public LlmRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty() || messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("messages must contain at least one message");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (tools.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("tools cannot contain null");
        }
    }

    public static LlmRequest of(String model, List<LlmMessage> messages) {
        return new LlmRequest(model, messages);
    }

    public static LlmRequest of(
            String model, List<LlmMessage> messages, List<ToolDefinition> tools) {
        return new LlmRequest(model, messages, tools);
    }
}
