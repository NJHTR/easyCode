package com.easycode.agent.adapter.trace;

import com.easycode.agent.api.AgentTraceQuery;
import com.easycode.agent.model.AgentRunTrace;
import com.easycode.agent.model.AgentStepTrace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read-only in-memory index of Agent traces; no persistence is involved. */
public final class InMemoryAgentTraceQuery implements AgentTraceQuery {
    private final Map<UUID, AgentRunTrace> traces;

    public InMemoryAgentTraceQuery(Collection<AgentRunTrace> traces) {
        Objects.requireNonNull(traces, "traces");
        Map<UUID, AgentRunTrace> index = new LinkedHashMap<>();
        for (AgentRunTrace trace : traces) {
            Objects.requireNonNull(trace, "traces cannot contain null");
            if (index.putIfAbsent(trace.runId(), trace) != null) {
                throw new IllegalArgumentException("duplicate trace runId: " + trace.runId());
            }
        }
        this.traces = Map.copyOf(index);
    }

    @Override
    public Optional<AgentRunTrace> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }
        AgentRunTrace trace = traces.get(runId);
        return trace == null ? Optional.empty() : Optional.of(sortedCopy(trace));
    }

    private static AgentRunTrace sortedCopy(AgentRunTrace trace) {
        List<AgentStepTrace> steps = new ArrayList<>(trace.steps());
        steps.sort(Comparator.comparingInt(AgentStepTrace::stepNumber));
        return new AgentRunTrace(
                trace.runId(), trace.requestId(), steps, trace.outcome(), trace.failureReason());
    }
}
