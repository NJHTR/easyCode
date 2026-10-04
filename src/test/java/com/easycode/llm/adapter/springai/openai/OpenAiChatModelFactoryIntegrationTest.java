package com.easycode.llm.adapter.springai.openai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatModelFactoryIntegrationTest {
    @Test
    void createsModelWithDefaultBaseUrlWithoutCallingNetwork() {
        OpenAiCompatibleModelConfig config =
                OpenAiCompatibleModelConfig.defaults("test-api-key", "test-model");

        OpenAiChatModel model = OpenAiChatModelFactory.create(config);
        OpenAiChatOptions options = model.getOptions();

        assertEquals(OpenAiCompatibleModelConfig.DEFAULT_BASE_URL, options.getBaseUrl());
        assertEquals("test-api-key", options.getApiKey());
        assertEquals("test-model", options.getModel());
    }

    @Test
    void preservesCustomCompatibleEndpoint() {
        OpenAiCompatibleModelConfig config = new OpenAiCompatibleModelConfig(
                "http://localhost:8000/v1", "test-api-key", "local-model");

        OpenAiChatOptions options = OpenAiChatModelFactory.create(config).getOptions();

        assertEquals("http://localhost:8000/v1", options.getBaseUrl());
        assertEquals("local-model", options.getModel());
    }

    @Test
    void rejectsMissingCredentialsAndInvalidBaseUrlEarly() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleModelConfig(
                        "https://api.openai.com", "", "test-model"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleModelConfig(
                        "not-a-url", "test-api-key", "test-model"));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiCompatibleModelConfig(
                        "file:///tmp/model", "test-api-key", "test-model"));
    }

    @Test
    void doesNotExposeApiKeyInToString() {
        OpenAiCompatibleModelConfig config = new OpenAiCompatibleModelConfig(
                "https://api.openai.com", "secret-test-key", "test-model");

        assertFalse(config.toString().contains("secret-test-key"));
        assertTrue(config.toString().contains("[REDACTED]"));
    }
}
