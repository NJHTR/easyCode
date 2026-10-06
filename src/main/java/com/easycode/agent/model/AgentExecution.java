package com.easycode.agent.model;

import java.util.Objects;

/** Final Agent result and its independent in-memory run trace. */
public record AgentExecution(AgentResult result, AgentRunTrace trace) {
    public AgentExecution {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(trace, "trace");
        AgentRun run = result.run();
        if (!run.runId().equals(trace.runId())) {
            throw new IllegalArgumentException(
                    "result and trace must refer to the same runId");
        }
        if (!run.requestId().equals(trace.requestId())) {
            throw new IllegalArgumentException(
                    "result and trace must refer to the same requestId");
        }
        if (run.status() != trace.outcome()) {
            throw new IllegalArgumentException(
                    "result and trace must have the same terminal status");
        }
        if (!Objects.equals(result.failureReason(), trace.failureReason())) {
            throw new IllegalArgumentException(
                    "result and trace must have the same failure reason");
        }
    }
}
