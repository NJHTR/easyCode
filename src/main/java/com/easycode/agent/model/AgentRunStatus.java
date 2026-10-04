package com.easycode.agent.model;

/** Lifecycle state of one synchronous Agent run. */
public enum AgentRunStatus {
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
