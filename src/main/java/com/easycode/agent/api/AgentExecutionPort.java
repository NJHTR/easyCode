package com.easycode.agent.api;

import com.easycode.agent.model.AgentRequest;
import com.easycode.execution.model.ExecutionResult;

/** Agent-facing execution boundary; runtime selection stays behind this port. */
@FunctionalInterface
public interface AgentExecutionPort {
    ExecutionResult execute(AgentRequest request);
}
