package com.easycode.agent.integration;

import com.easycode.agent.composition.LocalAgentComposition;
import com.easycode.agent.composition.LocalAgentSession;
import com.easycode.agent.api.AgentTraceQuery;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.api.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentSessionIntegrationTest {
    @Test
    void sessionCorrelatesMultipleRunsAndTheirTraces() {
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.text("first"), LlmResponse.text("second"));
        LocalAgentSession session = session(provider);

        AgentExecution first = session.runWithTrace(prompt());
        AgentExecution second = session.runWithTrace(prompt());

        assertNotEquals(first.result().run().runId(), second.result().run().runId());
        assertEquals(List.of(first, second), session.executions());
        assertEquals(first, session.findByRunId(first.result().run().runId()).orElseThrow());
        assertEquals(second.trace().runId(), second.result().run().runId());
        assertEquals("second", second.result().message());
    }

    @Test
    void runAlsoRecordsExecutionWhileReturningOnlyResult() {
        LocalAgentSession session = session(new RecordingProvider(LlmResponse.text("done")));

        var result = session.run(prompt());

        assertTrue(result.succeeded());
        assertEquals(1, session.executions().size());
        assertEquals(result.run().runId(), session.executions().get(0).result().run().runId());
    }

    @Test
    void sessionQueryIsInMemoryAndImmutable() {
        LocalAgentSession session = session(new RecordingProvider(LlmResponse.text("done")));
        AgentExecution execution = session.runWithTrace(prompt());

        assertTrue(session.findByRunId(UUID.randomUUID()).isEmpty());
        assertTrue(session.findByRunId(null).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> session.executions().clear());
        assertFalse(session.findByRunId(execution.result().run().runId()).isEmpty());
    }

    @Test
    void sessionExposesTraceQueryBackedByItsCompletedRuns() {
        LocalAgentSession session = session(new RecordingProvider(LlmResponse.text("done")));
        AgentTraceQuery query = session.traceQuery();

        AgentExecution execution = session.runWithTrace(prompt());

        assertEquals(execution.trace(),
                query.findByRunId(execution.result().run().runId()).orElseThrow());
        assertTrue(query.findByRunId(UUID.randomUUID()).isEmpty());
        assertTrue(query.findByRunId(null).isEmpty());
    }

    @Test
    void sessionTraceQueryRemainsIsolatedAndReadOnly() {
        LocalAgentSession first = session(new RecordingProvider(LlmResponse.text("first")));
        LocalAgentSession second = session(new RecordingProvider(LlmResponse.text("second")));
        AgentTraceQuery firstQuery = first.traceQuery();
        AgentExecution execution = first.runWithTrace(prompt());

        assertTrue(second.traceQuery().findByRunId(execution.result().run().runId()).isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> firstQuery.findByRunId(execution.result().run().runId())
                        .orElseThrow().steps().clear());
        assertEquals(1, firstQuery.findByRunId(execution.result().run().runId())
                .orElseThrow().steps().size());
    }

    @Test
    void sessionsHaveIndependentIdentitiesAndExecutionIndexes() {
        LocalAgentSession first = session(new RecordingProvider(LlmResponse.text("first")));
        LocalAgentSession second = session(new RecordingProvider(LlmResponse.text("second")));

        assertNotEquals(first.sessionId(), second.sessionId());
        first.run(prompt());
        assertEquals(1, first.executions().size());
        assertTrue(second.executions().isEmpty());
    }

    private static LocalAgentSession session(LlmProvider provider) {
        return new LocalAgentComposition(provider, new ToolRegistry(), 3).openSession();
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create("fake-model", List.of(LlmMessage.user("hello")));
    }

    private static final class RecordingProvider implements LlmProvider {
        private final Queue<LlmResponse> responses = new ArrayDeque<>();

        private RecordingProvider(LlmResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmResponse generate(com.easycode.llm.model.LlmRequest request) {
            return responses.remove();
        }
    }
}
