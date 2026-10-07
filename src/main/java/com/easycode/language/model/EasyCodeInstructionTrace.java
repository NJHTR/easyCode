package com.easycode.language.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable, in-memory observation of one instruction invocation. */
public record EasyCodeInstructionTrace(
        int sequence,
        String instructionId,
        Instant startedAt,
        Instant completedAt,
        EasyCodeInstructionTraceStatus status,
        String failureMessage) {

    public EasyCodeInstructionTrace {
        if (sequence < 1) {
            throw new IllegalArgumentException("trace sequence must be positive");
        }
        if (instructionId == null || instructionId.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot be before startedAt");
        }
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (status == EasyCodeInstructionTraceStatus.SUCCEEDED && !failureMessage.isEmpty()) {
            throw new IllegalArgumentException("successful trace cannot have a failure message");
        }
    }

    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }
}
