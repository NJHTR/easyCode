package com.easycode.agent.model;

/** Provider-neutral outcome of one LLM generation step. */
public enum AgentStepOutcome {
    TOOL_CALLS,
    COMPLETED,
    LLM_FAILURE,
    CANCELLED,
    TIMEOUT,
    INVALID_RESPONSE,
    MAX_STEPS_REACHED,
    MAX_TOOL_CALLS_REACHED
}
