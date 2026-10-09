package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasExecutionObservationValidationTest {
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
}
