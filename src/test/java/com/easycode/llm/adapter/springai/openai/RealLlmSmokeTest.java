package com.easycode.llm.adapter.springai.openai;

import com.easycode.llm.adapter.springai.SpringAiLlmProvider;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit external-provider smoke test; disabled unless all required env vars are present. */
@Tag("real-llm")
class RealLlmSmokeTest {
    @Test
    void configuredProviderReturnsNonEmptyResponse() {
        assumeTrue(isEnabled(), "EASYCODE_REAL_LLM_TEST is not true");

        String apiKey = environment("EASYCODE_LLM_API_KEY");
        assumeTrue(apiKey != null, "EASYCODE_LLM_API_KEY is not configured");

        String model = environment("EASYCODE_LLM_MODEL");
        assumeTrue(model != null, "EASYCODE_LLM_MODEL is not configured");

        String baseUrl = environment("EASYCODE_LLM_BASE_URL");
        if (baseUrl == null) {
            baseUrl = OpenAiCompatibleModelConfig.DEFAULT_BASE_URL;
        }

        LlmResponse response;
        try {
            OpenAiCompatibleModelConfig config =
                    new OpenAiCompatibleModelConfig(baseUrl, apiKey, model);
            LlmProvider provider = new SpringAiLlmProvider(
                    OpenAiChatModelFactory.create(config));
            response = provider.generate(new LlmRequest(
                    model,
                    List.of(LlmMessage.user(
                            "Reply with exactly: EASYCODE_SMOKE_OK"))));
        } catch (IllegalArgumentException exception) {
            fail("CONFIGURATION_ERROR");
            return;
        } catch (LlmException exception) {
            fail(classify(exception));
            return;
        }

        assertNotNull(response, "INVALID_RESPONSE");
        assertNotNull(response.content(), "INVALID_RESPONSE");
        assertFalse(response.content().trim().isEmpty(), "INVALID_RESPONSE");
    }

    private static boolean isEnabled() {
        return "true".equalsIgnoreCase(environment("EASYCODE_REAL_LLM_TEST"));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String classify(LlmException exception) {
        StringBuilder diagnostics = new StringBuilder();
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null) {
                diagnostics.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
        }
        String message = diagnostics.toString().toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403")
                || message.contains("unauthorized") || message.contains("forbidden")) {
            return "AUTHENTICATION_ERROR";
        }
        if (message.contains("timeout") || message.contains("connection")
                || message.contains("connect")) {
            return "NETWORK_ERROR";
        }
        if (message.contains("response") || message.contains("generation")) {
            return "INVALID_RESPONSE";
        }
        return "PROVIDER_ERROR";
    }
}
