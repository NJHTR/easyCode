package com.easycode.agent.model;

/** Provider-neutral outcome of one LLM generation step. */
public enum AgentStepOutcome {
    TOOL_CALLS,
    COMPLETED,
    LLM_FAILURE,
    TIMEOUT,
    INVALID_RESPONSE,
    MAX_STEPS_REACHED
}
