package com.easycode.agent.integration;

import com.easycode.agent.application.LocalAgentApplication;
import com.easycode.agent.composition.LocalAgentSession;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRun;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.agent.model.AgentRunTrace;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.api.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentApplicationIntegrationTest {
    @Test
    void offlineProviderCanBeWiredThroughApplicationIntoSession() {
        LocalAgentSession session = LocalAgentApplication.openSession(
                request -> LlmResponse.text("offline-ok"),
                new ToolRegistry(),
                3);

        var result = session.run(prompt());

        assertTrue(result.succeeded());
        assertEquals("offline-ok", result.message());
        assertEquals(1, session.executions().size());
    }

    @Test
    void openAiApplicationPathBuildsWithoutMakingARequest() {
        LocalAgentSession session = LocalAgentApplication.openOpenAiSession(
                new com.easycode.llm.adapter.springai.openai.OpenAiCompatibleModelConfig(
                        "https://example.com", "test-key", "test-model"),
                new ToolRegistry(),
                3);

        assertNotNull(session);
        assertNotNull(session.sessionId());
    }

    @Test
    void cliReportsSuccessfulResultOnStdoutAndZeroExitCode() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = LocalAgentApplication.reportExecution(
                execution(AgentRunStatus.SUCCEEDED, null, "ok"),
                new PrintStream(output), new PrintStream(error));

        assertEquals(0, exitCode);
        assertEquals("ok" + System.lineSeparator(), output.toString());
        assertTrue(error.toString().isEmpty());
    }

    @Test
    void cliReportsFailedResultOnStderrAndNonZeroExitCode() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = LocalAgentApplication.reportExecution(
                execution(AgentRunStatus.FAILED, AgentFailureReason.LLM_FAILURE,
                        "provider secret must not be printed"),
                new PrintStream(output), new PrintStream(error));

        assertEquals(1, exitCode);
        assertTrue(output.toString().isEmpty());
        assertTrue(error.toString().contains("Agent execution failed: FAILED"));
        assertTrue(error.toString().contains("LLM_FAILURE"));
        assertTrue(!error.toString().contains("provider secret"));
    }

    private static AgentExecution execution(
            AgentRunStatus status, AgentFailureReason reason, String message) {
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentRun run = new AgentRun(runId, requestId, status, now, now, now);
        AgentResult result = new AgentResult(run, reason, null, message);
        return new AgentExecution(result,
                new AgentRunTrace(runId, requestId, List.of(), status, reason));
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create(
                "fake-model", List.of(LlmMessage.user("hello")));
    }
}
