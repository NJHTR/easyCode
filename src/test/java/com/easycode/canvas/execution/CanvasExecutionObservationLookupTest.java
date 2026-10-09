package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecutionObservationLookupTest {
    @Test
    void looksUpPublishedNullAndNodeStatus() {
        UUID executionId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        Map<UUID, Object> publishedOutputs = new LinkedHashMap<>();
        publishedOutputs.put(outputPortId, null);
        CanvasExecutionObservation observation = new CanvasExecutionObservation(
                Optional.of(executionId),
                Optional.of(Instant.now()),
                Optional.empty(),
                Optional.of(Duration.ZERO),
                Optional.empty(),
                Map.of(),
                Optional.empty(),
                Map.of(),
                Map.of(nodeId, CanvasExecutionStatus.SUCCEEDED),
                List.of(nodeId),
                publishedOutputs,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false);

        assertTrue(observation.publishedOutput(outputPortId).present());
        assertEquals(null, observation.publishedOutput(outputPortId).value());
        assertEquals(false, observation.publishedOutput(UUID.randomUUID()).present());
        assertEquals(Optional.of(CanvasExecutionStatus.SUCCEEDED), observation.nodeStatus(nodeId));
        assertEquals(Optional.empty(), observation.nodeStatus(UUID.randomUUID()));
        assertThrows(NullPointerException.class, () -> observation.nodeStatus(null));
        assertThrows(NullPointerException.class, () -> observation.publishedOutput(null));
    }
}
