package com.easycode.execution.model;

/** Lifecycle state represented by an execution result. */
public enum ExecutionStatus {
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
