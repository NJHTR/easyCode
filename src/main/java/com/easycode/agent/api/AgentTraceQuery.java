package com.easycode.agent.api;

import com.easycode.agent.model.AgentRunTrace;

import java.util.Optional;
import java.util.UUID;

/** Read-only query boundary for completed in-memory Agent run traces. */
public interface AgentTraceQuery {
    Optional<AgentRunTrace> findByRunId(UUID runId);
}
