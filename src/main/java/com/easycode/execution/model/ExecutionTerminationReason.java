package com.easycode.execution.model;

/** Why an execution reached its terminal state. */
public enum ExecutionTerminationReason {
    COMPLETED,
    NON_ZERO_EXIT,
    TIMED_OUT,
    CANCELLED,
    START_FAILED,
    OUTPUT_LIMIT,
    INTERNAL_ERROR
}
