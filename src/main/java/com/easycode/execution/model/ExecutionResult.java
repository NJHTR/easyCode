package com.easycode.execution.model;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Unified result returned by host and sandbox execution backends. */
public record ExecutionResult(
        UUID executionId,
        ExecutionStatus status,
        Integer exitCode,
        String stdout,
        String stderr,
        Duration duration,
        ExecutionTerminationReason terminationReason,
        String failureMessage) {

    public ExecutionResult {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        Objects.requireNonNull(terminationReason, "terminationReason");
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }
}
