package com.easycode.canvas.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable point-in-time observation of one node invocation. */
public record CanvasNodeExecutionObservation(
        UUID nodeId,
        Optional<Instant> startedAt,
        Optional<Instant> completedAt,
        Optional<Duration> elapsed,
        Map<UUID, Object> inputs,
        Optional<Map<UUID, Object>> outputs,
        List<String> consoleOutput,
        Optional<CanvasExecutionStatus> status,
        Optional<String> failureMessage,
        Optional<CanvasExecutionFailure> failureDetails) {
    public CanvasNodeExecutionObservation {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureMessage, "failureMessage");
        Objects.requireNonNull(failureDetails, "failureDetails");
        inputs = CanvasExecutionValueSnapshots.snapshot(inputs, "inputs");
        outputs = outputs.map(values -> CanvasExecutionValueSnapshots.snapshot(values, "outputs"));
        consoleOutput = consoleOutput == null ? List.of() : List.copyOf(consoleOutput);
        if (consoleOutput.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("console output cannot contain null");
        }
        if (elapsed.isPresent() && elapsed.orElseThrow().isNegative()) {
            throw new IllegalArgumentException("elapsed cannot be negative");
        }
        if (failureMessage.isPresent() && failureMessage.orElseThrow().isBlank()) {
            throw new IllegalArgumentException("failure message cannot be blank");
        }
    }

    /** Returns whether this node is currently running or paused before execution. */
    public boolean isActive() {
        return status.isEmpty();
    }

    /** Returns whether this node is paused before its executor starts. */
    public boolean isPaused() {
        return isActive() && startedAt.isEmpty();
    }

    /** Looks up one observed input while preserving an explicit null value. */
    public CanvasValueLookup input(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return inputs.containsKey(portId)
                ? CanvasValueLookup.present(inputs.get(portId))
                : CanvasValueLookup.missing();
    }

    /** Looks up one observed output while preserving an explicit null value. */
    public CanvasValueLookup output(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return outputs.isPresent() && outputs.orElseThrow().containsKey(portId)
                ? CanvasValueLookup.present(outputs.orElseThrow().get(portId))
                : CanvasValueLookup.missing();
    }

    /** Returns whether this node reached a terminal outcome. */
    public boolean isComplete() {
        return status.isPresent();
    }
}
