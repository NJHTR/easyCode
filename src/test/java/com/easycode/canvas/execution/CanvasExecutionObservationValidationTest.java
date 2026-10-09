package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasExecutionObservationValidationTest {
    @Test
    void rejectsExecutionIdentityWithoutStartTime() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasExecutionObservation(
                Optional.of(UUID.randomUUID()),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false));
    }

    @Test
    void rejectsTerminalStatusWithoutCompleteSnapshot() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasExecutionObservation(
                Optional.of(UUID.randomUUID()),
                Optional.of(Instant.now()),
                Optional.empty(),
                Optional.of(Duration.ZERO),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                Optional.of(CanvasExecutionStatus.SUCCEEDED),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false));
    }

    @Test
    void rejectsFailedExecutionWithoutFailedNodeAndMessage() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasExecutionObservation(
                Optional.of(UUID.randomUUID()),
                Optional.of(Instant.now()),
                Optional.of(Instant.now()),
                Optional.of(Duration.ZERO),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(),
                Optional.of(CanvasExecutionStatus.FAILED),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                true));
    }

    @Test
    void rejectsActiveNodeWithTerminalOutput() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasNodeExecutionObservation(
                UUID.randomUUID(),
                Optional.of(Instant.now()),
                Optional.empty(),
                Optional.of(Duration.ZERO),
                Map.of(),
                Optional.of(Map.of()),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void rejectsFailedNodeWithoutFailureMessage() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasNodeExecutionObservation(
                UUID.randomUUID(),
                Optional.of(Instant.now()),
                Optional.of(Instant.now()),
                Optional.of(Duration.ZERO),
                Map.of(),
                Optional.of(Map.of()),
                List.of(),
                Optional.of(CanvasExecutionStatus.FAILED),
                Optional.empty(),
                Optional.empty()));
    }

    @Test
    void rejectsCompletedNodeListThatDisagreesWithNodeStatuses() {
        UUID nodeId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> observation(
                Optional.empty(), Optional.empty(), Map.of(nodeId, CanvasExecutionStatus.SUCCEEDED),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
    }

    @Test
    void rejectsActiveNodeThatAlreadyHasTerminalStatus() {
        UUID nodeId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> observation(
                Optional.of(nodeId), Optional.empty(), Map.of(nodeId, CanvasExecutionStatus.SUCCEEDED),
                List.of(nodeId), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
    }

    @Test
    void rejectsFailedNodeIdentityThatDisagreesWithNodeStatuses() {
        UUID failedNodeId = UUID.randomUUID();
        UUID otherNodeId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> observation(
                Optional.empty(), Optional.empty(), Map.of(otherNodeId, CanvasExecutionStatus.FAILED),
                List.of(), Optional.empty(), Optional.of(failedNodeId), Optional.empty(), Optional.empty(), false));
    }

    @Test
    void allowsNodeFailureObservationBeforeExecutionTerminalEvent() {
        UUID failedNodeId = UUID.randomUUID();
        assertDoesNotThrow(() -> observation(
                Optional.empty(), Optional.empty(), Map.of(failedNodeId, CanvasExecutionStatus.FAILED),
                List.of(), Optional.empty(), Optional.of(failedNodeId), Optional.empty(), Optional.empty(), false));
    }

    @Test
    void allowsNodeCancellationObservationBeforeExecutionTerminalEvent() {
        UUID cancelledNodeId = UUID.randomUUID();
        assertDoesNotThrow(() -> observation(
                Optional.empty(), Optional.empty(), Map.of(cancelledNodeId, CanvasExecutionStatus.CANCELLED),
                List.of(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false));
    }

    private static CanvasExecutionObservation observation(
            Optional<UUID> activeNodeId,
            Optional<UUID> pausedNodeId,
            Map<UUID, CanvasExecutionStatus> nodeStatuses,
            List<UUID> completedNodeIds,
            Optional<CanvasExecutionStatus> terminalStatus,
            Optional<UUID> failedNodeId,
            Optional<CanvasExecutionFailure> failureDetails,
            Optional<String> terminalMessage,
            boolean complete) {
        return new CanvasExecutionObservation(
                Optional.of(UUID.randomUUID()),
                Optional.of(Instant.EPOCH),
                complete ? Optional.of(Instant.EPOCH.plusSeconds(1)) : Optional.empty(),
                Optional.of(complete ? Duration.ofSeconds(1) : Duration.ZERO),
                activeNodeId,
                Map.of(),
                pausedNodeId,
                Map.of(),
                nodeStatuses,
                completedNodeIds,
                Map.of(),
                List.of(),
                terminalStatus,
                failedNodeId,
                failureDetails,
                terminalMessage,
                complete);
    }
}
