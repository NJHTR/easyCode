package com.easycode.llm.adapter.springai.openai;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Objects;

/** Creates a Spring AI OpenAI-compatible ChatModel without performing a call. */
public final class OpenAiChatModelFactory {
    private OpenAiChatModelFactory() {
    }

    public static OpenAiChatModel create(OpenAiCompatibleModelConfig config) {
        Objects.requireNonNull(config, "config");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .model(config.model())
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .build();
    }
}
