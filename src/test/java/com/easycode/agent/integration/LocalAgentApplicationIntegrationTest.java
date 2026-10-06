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
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.agent.model.AgentStepTrace;
import com.easycode.tool.model.ToolFailureReason;
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

    @Test
    void cliTraceSummaryReportsSafeSuccessfulStepMetadata() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String secretPrompt = "prompt must not appear";
        String secretToolInput = "tool input must not appear";
        String secretToolOutput = "tool output must not appear";
        AgentExecution execution = executionWithStep(
                AgentRunStatus.SUCCEEDED, null,
                new AgentStepTrace.ToolObservation(
                        UUID.randomUUID(), "safe-tool", true, null,
                        true, true, secretToolInput.length(), secretToolOutput.length()),
                AgentStepOutcome.TOOL_CALLS);

        int exitCode = LocalAgentApplication.reportTraceSummary(
                execution, new PrintStream(output), new PrintStream(error));

        String summary = output.toString();
        assertEquals(0, exitCode);
        assertTrue(summary.contains("runId=" + execution.trace().runId()));
        assertTrue(summary.contains("requestId=" + execution.trace().requestId()));
        assertTrue(summary.contains("status=SUCCEEDED"));
        assertTrue(summary.contains("failureReason=NONE"));
        assertTrue(summary.contains("durationMs=10"));
        assertTrue(summary.contains("steps=1"));
        assertTrue(summary.contains("step[1].outcome=TOOL_CALLS"));
        assertTrue(summary.contains("step[1].durationMs=10"));
        assertTrue(summary.contains("step[1].toolCalls=1"));
        assertTrue(summary.contains("step[1].toolObservations=1"));
        assertTrue(summary.contains("step[1].successfulToolObservations=1"));
        assertTrue(summary.contains("step[1].failedToolObservations=0"));
        assertTrue(summary.contains("step[1].toolFailureReasons=NONE"));
        assertTrue(!summary.contains(secretPrompt));
        assertTrue(!summary.contains(secretToolInput));
        assertTrue(!summary.contains(secretToolOutput));
        assertTrue(error.toString().isEmpty());
    }

    @Test
    void cliTraceSummaryReportsFailureAndFailedToolObservation() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        AgentExecution execution = executionWithStep(
                AgentRunStatus.FAILED, AgentFailureReason.LLM_FAILURE,
                new AgentStepTrace.ToolObservation(
                        UUID.randomUUID(), "safe-tool", false,
                        ToolFailureReason.INTERNAL_ERROR,
                        true, true, 4, 6),
                AgentStepOutcome.LLM_FAILURE);

        int exitCode = LocalAgentApplication.reportTraceSummary(
                execution, new PrintStream(output), new PrintStream(error));

        String summary = output.toString();
        assertEquals(1, exitCode);
        assertTrue(summary.contains("status=FAILED"));
        assertTrue(summary.contains("failureReason=LLM_FAILURE"));
        assertTrue(summary.contains("step[1].outcome=LLM_FAILURE"));
        assertTrue(summary.contains("step[1].failedToolObservations=1"));
        assertTrue(summary.contains("step[1].toolFailureReasons=INTERNAL_ERROR:1"));
        assertTrue(error.toString().isEmpty());
    }

    @Test
    void cliTraceSummaryReportsToolTimeoutReason() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        AgentExecution execution = executionWithStep(
                AgentRunStatus.FAILED, AgentFailureReason.LLM_FAILURE,
                new AgentStepTrace.ToolObservation(
                        UUID.randomUUID(), "slow-tool", false,
                        ToolFailureReason.TIMEOUT,
                        true, true, 4, 6),
                AgentStepOutcome.TOOL_CALLS);

        int exitCode = LocalAgentApplication.reportTraceSummary(
                execution, new PrintStream(output), new PrintStream(error));

        assertEquals(1, exitCode);
        assertTrue(output.toString().contains("step[1].toolFailureReasons=TIMEOUT:1"));
        assertTrue(error.toString().isEmpty());
    }

    @Test
    void cliTraceSummaryReportsTimeoutWithoutResultMessage() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        AgentExecution execution = executionWithStep(
                AgentRunStatus.TIMED_OUT, AgentFailureReason.TIMEOUT,
                null, AgentStepOutcome.TIMEOUT);

        int exitCode = LocalAgentApplication.reportTraceSummary(
                execution, new PrintStream(output), new PrintStream(error));

        String summary = output.toString();
        assertEquals(1, exitCode);
        assertTrue(summary.contains("status=TIMED_OUT"));
        assertTrue(summary.contains("failureReason=TIMEOUT"));
        assertTrue(summary.contains("step[1].outcome=TIMEOUT"));
        assertTrue(summary.contains("step[1].toolObservations=0"));
        assertTrue(!summary.contains("timeout result must not be printed"));
        assertTrue(error.toString().isEmpty());
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

    private static AgentExecution executionWithStep(
            AgentRunStatus status, AgentFailureReason reason,
            AgentStepTrace.ToolObservation observation, AgentStepOutcome outcome) {
        Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant completedAt = Instant.parse("2026-01-01T00:00:00.010Z");
        AgentStepTrace step = new AgentStepTrace(
                1, startedAt, completedAt, "test-model", 1, 1,
                outcome == AgentStepOutcome.COMPLETED,
                observation == null ? 0 : 1,
                observation == null ? List.of() : List.of(observation), outcome);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID runId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AgentRun run = new AgentRun(runId, requestId, status, now, now, completedAt);
        AgentResult result = new AgentResult(run, reason, null,
                "timeout result must not be printed");
        return new AgentExecution(result,
                new AgentRunTrace(runId, requestId, List.of(step), status, reason));
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create(
                "fake-model", List.of(LlmMessage.user("hello")));
    }
}
