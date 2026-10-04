package com.easycode.llm.model;

import java.util.Objects;

/** One message sent to an LLM provider. */
public record LlmMessage(LlmMessageRole role, String content) {
    public LlmMessage {
        Objects.requireNonNull(role, "role");
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
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
}
