package com.easycode.canvas.execution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable report describing whether one prepared canvas request can start. */
public record CanvasExecutionPreflightResult(
        UUID executionId,
        UUID canvasId,
        List<UUID> plannedNodeIds,
        List<CanvasExecutionDiagnostic> diagnostics) {
    public CanvasExecutionPreflightResult(UUID canvasId,
                                          List<UUID> plannedNodeIds,
                                          List<CanvasExecutionDiagnostic> diagnostics) {
        this(UUID.randomUUID(), canvasId, plannedNodeIds, diagnostics);
    }

    public CanvasExecutionPreflightResult {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(canvasId, "canvasId");
        plannedNodeIds = plannedNodeIds == null ? List.of() : List.copyOf(plannedNodeIds);
        if (plannedNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("planned node ids cannot contain null");
        }
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (diagnostics.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("diagnostics cannot contain null");
        }
    }

    public boolean ready() {
        return diagnostics.isEmpty();
    }

    public static CanvasExecutionPreflightResult invalidRequest(UUID executionId, UUID canvasId, String message) {
        return new CanvasExecutionPreflightResult(executionId, canvasId, List.of(), List.of(
                new CanvasExecutionDiagnostic(CanvasExecutionDiagnosticCode.INVALID_REQUEST, message, null, null)));
    }
}
