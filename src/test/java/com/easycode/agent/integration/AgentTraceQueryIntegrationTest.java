package com.easycode.agent.integration;

import com.easycode.agent.adapter.trace.InMemoryAgentTraceQuery;
import com.easycode.agent.api.AgentTraceQuery;
import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.agent.model.AgentRunTrace;
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.agent.model.AgentStepTrace;
import com.easycode.tool.model.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTraceQueryIntegrationTest {
    @Test
    void findsExistingTraceByRunId() {
        AgentRunTrace trace = successTrace(List.of(step(1, AgentStepOutcome.COMPLETED)));
        AgentTraceQuery query = new InMemoryAgentTraceQuery(List.of(trace));

        AgentRunTrace found = query.findByRunId(trace.runId()).orElseThrow();

        assertEquals(trace.runId(), found.runId());
        assertEquals(trace.requestId(), found.requestId());
        assertEquals(trace.outcome(), found.outcome());
    }

    @Test
    void returnsEmptyForUnknownRunId() {
        AgentTraceQuery query = new InMemoryAgentTraceQuery(List.of());

        assertTrue(query.findByRunId(UUID.randomUUID()).isEmpty());
        assertTrue(query.findByRunId(null).isEmpty());
    }

    @Test
    void returnsStepsInAscendingOrderWithoutChangingOriginalTrace() {
        AgentStepTrace step3 = step(3, AgentStepOutcome.COMPLETED);
        AgentStepTrace step1 = step(1, AgentStepOutcome.TOOL_CALLS);
        AgentStepTrace step2 = step(2, AgentStepOutcome.COMPLETED);
        AgentRunTrace original = successTrace(List.of(step3, step1, step2));
        AgentTraceQuery query = new InMemoryAgentTraceQuery(List.of(original));

        AgentRunTrace found = query.findByRunId(original.runId()).orElseThrow();

        assertEquals(List.of(1, 2, 3), found.steps().stream()
                .map(AgentStepTrace::stepNumber).toList());
        assertEquals(List.of(3, 1, 2), original.steps().stream()
                .map(AgentStepTrace::stepNumber).toList());
        assertNotSame(original, found);
    }

    @Test
    void findsFailureTraceAndPreservesFailureReason() {
        AgentRunTrace trace = new AgentRunTrace(
                UUID.randomUUID(), UUID.randomUUID(), List.of(step(1, AgentStepOutcome.LLM_FAILURE)),
                AgentRunStatus.FAILED, AgentFailureReason.LLM_FAILURE);

        AgentRunTrace found = new InMemoryAgentTraceQuery(List.of(trace))
                .findByRunId(trace.runId()).orElseThrow();

        assertEquals(AgentRunStatus.FAILED, found.outcome());
        assertEquals(AgentFailureReason.LLM_FAILURE, found.failureReason());
    }

    @Test
    void preservesToolObservationFields() {
        UUID callId = UUID.randomUUID();
        AgentStepTrace.ToolObservation observation = new AgentStepTrace.ToolObservation(
                callId, "test.echo", false, ToolFailureReason.TOOL_NOT_FOUND,
                true, true, 7, 14);
        AgentStepTrace step = new AgentStepTrace(
                1, Instant.now(), Instant.now(), "model", 1, 1, false, 1,
                List.of(observation), AgentStepOutcome.TOOL_CALLS);
        AgentRunTrace trace = successTrace(List.of(step));

        AgentStepTrace.ToolObservation found = new InMemoryAgentTraceQuery(List.of(trace))
                .findByRunId(trace.runId()).orElseThrow().steps().get(0)
                .toolObservations().get(0);

        assertEquals("test.echo", found.toolName());
        assertEquals(callId, found.callId());
        assertFalse(found.succeeded());
        assertEquals(ToolFailureReason.TOOL_NOT_FOUND, found.failureReason());
        assertTrue(found.inputPresent());
        assertTrue(found.outputPresent());
        assertEquals(7, found.inputLength());
        assertEquals(14, found.outputLength());
    }

    @Test
    void queryResultsCannotMutateIndexedTrace() {
        AgentRunTrace trace = successTrace(List.of(step(1, AgentStepOutcome.COMPLETED)));
        AgentTraceQuery query = new InMemoryAgentTraceQuery(List.of(trace));

        AgentRunTrace found = query.findByRunId(trace.runId()).orElseThrow();

        assertThrows(UnsupportedOperationException.class,
                () -> found.steps().clear());
        assertEquals(1, query.findByRunId(trace.runId()).orElseThrow().steps().size());
    }

    @Test
    void indexesMultipleRunsIndependently() {
        AgentRunTrace first = successTrace(List.of(step(1, AgentStepOutcome.COMPLETED)));
        AgentRunTrace second = successTrace(List.of(step(1, AgentStepOutcome.INVALID_RESPONSE)));
        AgentTraceQuery query = new InMemoryAgentTraceQuery(List.of(first, second));

        assertEquals(first.runId(), query.findByRunId(first.runId()).orElseThrow().runId());
        assertEquals(second.runId(), query.findByRunId(second.runId()).orElseThrow().runId());
        assertEquals(AgentStepOutcome.INVALID_RESPONSE,
                query.findByRunId(second.runId()).orElseThrow().steps().get(0).outcome());
    }

    @Test
    void rejectsDuplicateRunIds() {
        AgentRunTrace first = successTrace(List.of(step(1, AgentStepOutcome.COMPLETED)));
        AgentRunTrace duplicate = new AgentRunTrace(
                first.runId(), UUID.randomUUID(), List.of(step(1, AgentStepOutcome.COMPLETED)),
                AgentRunStatus.SUCCEEDED, null);

        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryAgentTraceQuery(List.of(first, duplicate)));
    }

    private static AgentRunTrace successTrace(List<AgentStepTrace> steps) {
        return new AgentRunTrace(
                UUID.randomUUID(), UUID.randomUUID(), steps, AgentRunStatus.SUCCEEDED, null);
    }

    private static AgentStepTrace step(int number, AgentStepOutcome outcome) {
        Instant now = Instant.now();
        return new AgentStepTrace(number, now, now, "model", 1, 1,
                outcome == AgentStepOutcome.COMPLETED, 0, List.of(), outcome);
    }
}
