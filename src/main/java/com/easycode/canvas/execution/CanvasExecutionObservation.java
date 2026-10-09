package com.easycode.canvas.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable point-in-time view of the state collected for one canvas execution.
 * It is a query snapshot, not a live event stream or a persistence model.
 */
public record CanvasExecutionObservation(
        Optional<UUID> executionId,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        Optional<Duration> elapsed,
        Optional<UUID> activeNodeId,
        Map<UUID, Object> activeNodeInputs,
        Optional<UUID> pausedNodeId,
        Map<UUID, Object> pausedNodeInputs,
        Map<UUID, CanvasExecutionStatus> nodeStatuses,
        List<UUID> completedNodeIds,
        Map<UUID, Object> publishedOutputValues,
        List<String> consoleOutput,
        Optional<CanvasExecutionStatus> terminalStatus,
        Optional<UUID> failedNodeId,
        Optional<CanvasExecutionFailure> failureDetails,
        Optional<String> terminalMessage,
        boolean complete) {
    public CanvasExecutionObservation {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(activeNodeId, "activeNodeId");
        Objects.requireNonNull(pausedNodeId, "pausedNodeId");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(failedNodeId, "failedNodeId");
        Objects.requireNonNull(failureDetails, "failureDetails");
        Objects.requireNonNull(terminalMessage, "terminalMessage");
        activeNodeInputs = immutableValues(activeNodeInputs, "activeNodeInputs");
        pausedNodeInputs = immutableValues(pausedNodeInputs, "pausedNodeInputs");
        nodeStatuses = immutableStatuses(nodeStatuses);
        completedNodeIds = completedNodeIds == null ? List.of() : List.copyOf(completedNodeIds);
        if (completedNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("completed node ids cannot contain null");
        }
        publishedOutputValues = immutableValues(publishedOutputValues, "publishedOutputValues");
        consoleOutput = consoleOutput == null ? List.of() : List.copyOf(consoleOutput);
        if (consoleOutput.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("console output cannot contain null");
        }
        if (elapsed.isPresent() && elapsed.orElseThrow().isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
    }

    private static Map<UUID, Object> immutableValues(Map<UUID, Object> values, String name) {
        if (values == null) {
            return Map.of();
        }
        return CanvasExecutionValueSnapshots.snapshot(values, name);
    }

    private static Map<UUID, CanvasExecutionStatus> immutableStatuses(
            Map<UUID, CanvasExecutionStatus> statuses) {
        if (statuses == null) {
            return Map.of();
        }
        if (statuses.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null)) {
            throw new IllegalArgumentException("node statuses cannot contain null entries");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(statuses));
    }

    /** Returns whether this snapshot represents an execution that has started but not terminated. */
    public boolean isRunning() {
        return executionId.isPresent() && !complete;
    }

    /** Returns whether the execution is paused at a node boundary in this snapshot. */
    public boolean isPaused() {
        return pausedNodeId.isPresent();
    }
}
