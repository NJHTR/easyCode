package com.easycode.agent.composition;

import com.easycode.agent.api.AgentTraceQuery;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRunTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous, in-memory boundary for one local Agent session.
 *
 * <p>A session correlates completed requests with their result and trace. It
 * does not persist data, append conversation history, schedule work, or add
 * memory semantics. Callers must provide the complete prompt for every run.</p>
 */
public final class LocalAgentSession {
    private final UUID sessionId;
    private final LocalAgentComposition composition;
    private final Map<UUID, AgentExecution> executions = new LinkedHashMap<>();

    public LocalAgentSession(LocalAgentComposition composition) {
        this(UUID.randomUUID(), composition);
    }

    LocalAgentSession(UUID sessionId, LocalAgentComposition composition) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    public UUID sessionId() {
        return sessionId;
    }

    /** Runs one complete prompt and records its result and trace in this session. */
    public synchronized AgentResult run(AgentPromptRequest request) {
        return runWithTrace(request).result();
    }

    /** Runs one complete prompt and records the resulting execution in this session. */
    public synchronized AgentExecution runWithTrace(AgentPromptRequest request) {
        AgentExecution execution = composition.runWithTrace(
                Objects.requireNonNull(request, "request"));
        UUID runId = execution.result().run().runId();
        if (executions.putIfAbsent(runId, execution) != null) {
            throw new IllegalStateException("duplicate Agent run id: " + runId);
        }
        return execution;
    }

    /** Finds a completed execution previously run through this session. */
    public synchronized Optional<AgentExecution> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(executions.get(runId));
    }

    /**
     * Returns a read-only trace view backed by this session's completed runs.
     * The view is live for subsequent runs, but it never exposes mutable
     * session state or traces belonging to another session.
     */
    public AgentTraceQuery traceQuery() {
        return new SessionTraceQuery();
    }

    /** Returns a stable insertion-order snapshot of this session's executions. */
    public synchronized List<AgentExecution> executions() {
        return List.copyOf(new ArrayList<>(executions.values()));
    }

    private final class SessionTraceQuery implements AgentTraceQuery {
        @Override
        public Optional<AgentRunTrace> findByRunId(UUID runId) {
            if (runId == null) {
                return Optional.empty();
            }
            synchronized (LocalAgentSession.this) {
                AgentExecution execution = executions.get(runId);
                return execution == null
                        ? Optional.empty()
                        : Optional.of(execution.trace());
            }
        }
    }
}
