package com.easycode.canvas.execution;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecutionObservationCursorTest {
    @Test
    void exposesLastEventAndStableObservedNodeOrder() {
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();
        UUID executionId = UUID.randomUUID();
        UUID firstNode = UUID.randomUUID();
        UUID secondNode = UUID.randomUUID();

        assertTrue(collector.lastEvent().isEmpty());
        assertEquals(List.of(), collector.observedNodeIds());

        CanvasExecutionEvent started = new CanvasExecutionEvent(executionId,
                CanvasExecutionEventType.STARTED, null, "", 0L);
        collector.onEvent(started);
        assertEquals(started, collector.lastEvent().orElseThrow());

        CanvasExecutionEvent firstStarted = new CanvasExecutionEvent(executionId,
                CanvasExecutionEventType.NODE_STARTED, firstNode, "", 1L);
        collector.onEvent(firstStarted);
        collector.onEvent(new CanvasExecutionEvent(executionId,
                CanvasExecutionEventType.NODE_SUCCEEDED, firstNode, "", 2L,
                Map.of(), Map.of(), List.of()));
        collector.onEvent(new CanvasExecutionEvent(executionId,
                CanvasExecutionEventType.NODE_STARTED, secondNode, "", 3L));

        assertEquals(List.of(firstNode, secondNode), collector.observedNodeIds());
        assertEquals(CanvasExecutionEventType.NODE_STARTED,
                collector.lastEvent().orElseThrow().type());
        assertThrows(UnsupportedOperationException.class,
                () -> collector.observedNodeIds().clear());
    }

    @Test
    void resultExposesTraceNodeOrder() {
        UUID firstNode = UUID.randomUUID();
        UUID secondNode = UUID.randomUUID();
        CanvasExecutionResult result = new CanvasExecutionResult(
                UUID.randomUUID(),
                UUID.randomUUID(),
                CanvasExecutionStatus.SUCCEEDED,
                List.of(firstNode, secondNode),
                List.of(trace(firstNode), trace(secondNode)),
                List.of(),
                Map.of(),
                Map.of(),
                null,
                "",
                Duration.ZERO);

        assertEquals(List.of(firstNode, secondNode), result.observedNodeIds());
        assertThrows(UnsupportedOperationException.class, () -> result.observedNodeIds().clear());
    }

    private static CanvasNodeExecutionTrace trace(UUID nodeId) {
        return new CanvasNodeExecutionTrace(nodeId, "test", CanvasExecutionStatus.SUCCEEDED,
                Map.of(), Map.of(), List.of(), Duration.ZERO, "");
    }
}
