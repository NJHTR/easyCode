package com.easycode.llm.adapter.springai.openai;

import java.net.URI;

/** Minimal configuration for an OpenAI-compatible Spring AI chat model. */
public record OpenAiCompatibleModelConfig(String baseUrl, String apiKey, String model) {
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";

    public OpenAiCompatibleModelConfig {
        baseUrl = requireHttpUrl(baseUrl);
        apiKey = requireNonBlank(apiKey, "apiKey");
        model = requireNonBlank(model, "model");
    }

    public static OpenAiCompatibleModelConfig defaults(String apiKey, String model) {
        return new OpenAiCompatibleModelConfig(DEFAULT_BASE_URL, apiKey, model);
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleModelConfig[baseUrl=" + baseUrl
                + ", apiKey=[REDACTED], model=" + model + "]";
    }

    private static String requireHttpUrl(String value) {
        String url = requireNonBlank(value, "baseUrl");
        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl must be a valid HTTP(S) URL", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be a valid HTTP(S) URL");
        }
        return url;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
