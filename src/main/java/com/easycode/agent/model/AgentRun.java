package com.easycode.agent.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Lifecycle snapshot for one Agent request. */
public record AgentRun(
        UUID runId,
        UUID requestId,
        AgentRunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public AgentRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (startedAt != null && startedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt cannot be before createdAt");
        }
        if (finishedAt != null && startedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt cannot be before startedAt");
        }
    }

    public boolean terminal() {
        return status == AgentRunStatus.SUCCEEDED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.TIMED_OUT
                || status == AgentRunStatus.CANCELLED;
    }

    /** Returns the elapsed run duration when the run has started and finished. */
    public Duration duration() {
        if (startedAt == null || finishedAt == null) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, finishedAt);
    }
}
