package com.easycode.agent.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentApplicationLaunchabilityIntegrationTest {
    @Test
    void missingApiKeyFailsBeforeAnyNetworkCallWithoutExposingCredentials() throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.easycode.agent.application.LocalAgentApplication",
                "offline launch check");
        Map<String, String> environment = processBuilder.environment();
        environment.remove("EASYCODE_LLM_API_KEY");
        environment.put("EASYCODE_LLM_MODEL", "offline-model");
        environment.remove("EASYCODE_LLM_BASE_URL");
        environment.remove("EASYCODE_AGENT_MAX_STEPS");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(1, process.waitFor());
        assertTrue(output.contains("EASYCODE_LLM_API_KEY is not configured"));
        assertFalse(output.contains("offline-secret"));
    }

    @Test
    void helpIsAvailableOfflineWithoutLlmConfiguration() throws Exception {
        for (String option : new String[]{"--help", "-h"}) {
            assertHelpProcess(option);
        }
    }

    private static void assertHelpProcess(String option) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-cp",
                System.getProperty("java.class.path"),
                "com.easycode.agent.application.LocalAgentApplication",
                option);
        Map<String, String> environment = processBuilder.environment();
        environment.remove("EASYCODE_LLM_API_KEY");
        environment.remove("EASYCODE_LLM_MODEL");
        environment.remove("EASYCODE_LLM_BASE_URL");
        environment.remove("EASYCODE_AGENT_MAX_STEPS");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String help = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor());
        assertTrue(help.contains("Usage: LocalAgentApplication <prompt>"));
        assertTrue(help.contains("EASYCODE_LLM_API_KEY"));
        assertTrue(help.contains("EASYCODE_LLM_MODEL"));
        assertTrue(help.contains("EASYCODE_LLM_BASE_URL"));
        assertTrue(help.contains("EASYCODE_AGENT_MAX_STEPS"));
        assertFalse(help.contains("is not configured"));
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
