package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasExecutionResultValidationTest {
    @Test
    void requiresCompletedNodesToMatchSuccessfulTracesInOrder() {
        UUID nodeId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.SUCCEEDED,
                List.of(nodeId),
                List.of(),
                null,
                ""));
        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.SUCCEEDED,
                List.of(),
                List.of(trace(nodeId, CanvasExecutionStatus.SUCCEEDED, "")),
                null,
                ""));
    }

    @Test
    void rejectsDuplicateNodeTracesAndCompletedIds() {
        UUID nodeId = UUID.randomUUID();
        CanvasNodeExecutionTrace successful = trace(nodeId, CanvasExecutionStatus.SUCCEEDED, "");

        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.SUCCEEDED,
                List.of(nodeId, nodeId),
                List.of(successful, successful),
                null,
                ""));
        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.SUCCEEDED,
                List.of(nodeId, nodeId),
                List.of(successful),
                null,
                ""));
    }

    @Test
    void failedResultMustEndWithItsFailedNodeTrace() {
        UUID failedNodeId = UUID.randomUUID();
        UUID otherNodeId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.FAILED,
                List.of(),
                List.of(trace(otherNodeId, CanvasExecutionStatus.FAILED, "broken")),
                failedNodeId,
                "broken"));
    }

    @Test
    void cancelledResultCannotContainFailedNodeTrace() {
        assertThrows(IllegalArgumentException.class, () -> result(
                CanvasExecutionStatus.CANCELLED,
                List.of(),
                List.of(trace(UUID.randomUUID(), CanvasExecutionStatus.FAILED, "broken")),
                null,
                ""));
    }

    private static CanvasExecutionResult result(CanvasExecutionStatus status,
                                                List<UUID> completedNodeIds,
                                                List<CanvasNodeExecutionTrace> nodeTraces,
                                                UUID failedNodeId,
                                                String failureMessage) {
        return new CanvasExecutionResult(UUID.randomUUID(), UUID.randomUUID(), status,
                completedNodeIds, nodeTraces, List.of(), Map.of(), Map.of(),
                failedNodeId, failureMessage, Duration.ZERO);
    }

    private static CanvasNodeExecutionTrace trace(UUID nodeId,
                                                  CanvasExecutionStatus status,
                                                  String failureMessage) {
        return new CanvasNodeExecutionTrace(nodeId, "test", status,
                Map.of(), Map.of(), List.of(), Duration.ZERO, failureMessage);
    }
}
