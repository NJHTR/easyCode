package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable summary of one synchronous canvas run. */
public record CanvasExecutionResult(
        UUID canvasId,
        CanvasExecutionStatus status,
        List<UUID> completedNodeIds,
        UUID failedNodeId,
        String failureMessage,
        Duration duration) {
    public CanvasExecutionResult {
        Objects.requireNonNull(canvasId, "canvasId");
        Objects.requireNonNull(status, "status");
        completedNodeIds = completedNodeIds == null ? List.of() : List.copyOf(completedNodeIds);
        if (completedNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("completed node ids cannot contain null");
        }
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (status == CanvasExecutionStatus.SUCCEEDED && failedNodeId != null) {
            throw new IllegalArgumentException("successful execution cannot have a failed node");
        }
        if (status == CanvasExecutionStatus.FAILED
                && (failedNodeId == null || failureMessage.isBlank())) {
            throw new IllegalArgumentException("failed execution must identify a node and failure");
        }
    }
}
