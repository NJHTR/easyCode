package com.easycode.agent.model;

/** Agent-level classification of a failed run. */
public enum AgentFailureReason {
    EXECUTION_FAILURE,
    RUNTIME_FAILURE,
    ENVIRONMENT_FAILURE,
    TIMEOUT,
    CANCELLED
}
