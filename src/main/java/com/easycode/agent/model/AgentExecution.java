package com.easycode.agent.model;

import java.util.Objects;

/** Final Agent result and its independent in-memory run trace. */
public record AgentExecution(AgentResult result, AgentRunTrace trace) {
    public AgentExecution {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(trace, "trace");
    }
}
