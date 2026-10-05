package com.easycode.agent.model;

/** Agent-level classification of a failed run. */
public enum AgentFailureReason {
    EXECUTION_FAILURE,
    RUNTIME_FAILURE,
    ENVIRONMENT_FAILURE,
    TIMEOUT,
    CANCELLED,
    LLM_FAILURE,
    INVALID_RESPONSE,
    MAX_STEPS_REACHED
}
