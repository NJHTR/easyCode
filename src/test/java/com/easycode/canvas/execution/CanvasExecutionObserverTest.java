package com.easycode.canvas.execution;

import com.easycode.canvas.api.CanvasApplication;
import com.easycode.canvas.api.CanvasService;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecutionObserverTest {
    @Test
    void observesSuccessfulLifecycleInExecutionOrder() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode first = application.createNode(CanvasBuiltinExecutors.CONSTANT, "first",
                Map.of("value", "one"));
        CanvasNode second = application.createNode(CanvasBuiltinExecutors.PRINT, "second");
        CanvasDefinition canvas = application.newCanvas("observed-success")
                .addNode(first)
                .addNode(second)
                .connect(first.nodeId(), "out", second.nodeId(), "in")
                .build();
        CanvasExecutionRequest request = CanvasExecutionRequest.forCanvas(canvas);
        List<CanvasExecutionEvent> events = new ArrayList<>();

        CanvasExecutionResult result = application.execute(request, events::add);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(
                CanvasExecutionEventType.STARTED,
                CanvasExecutionEventType.NODE_STARTED,
                CanvasExecutionEventType.NODE_SUCCEEDED,
                CanvasExecutionEventType.NODE_STARTED,
                CanvasExecutionEventType.NODE_SUCCEEDED,
                CanvasExecutionEventType.SUCCEEDED), events.stream().map(CanvasExecutionEvent::type).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L),
                events.stream().map(CanvasExecutionEvent::sequence).toList());
        assertEquals(first.nodeId(), events.get(1).nodeId());
        assertEquals(first.nodeId(), events.get(2).nodeId());
        assertEquals(second.nodeId(), events.get(3).nodeId());
        assertEquals(second.nodeId(), events.get(4).nodeId());
        assertTrue(events.stream().allMatch(event -> request.executionId().equals(event.executionId())));
        assertEquals(request.executionId(), result.executionId());
    }

    @Test
    void observesFailedNodeAndTerminalFailure() {
        UUID nodeId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "broken", "broken", Map.of(), List.of());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "observed-failure",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "broken", (ignoredNode, ignoredContext) -> {
                    throw new IllegalStateException("expected failure");
                }));
        CanvasExecutionRequest request = CanvasExecutionRequest.forCanvas(canvas);
        List<CanvasExecutionEvent> events = new ArrayList<>();

        CanvasExecutionResult result = service.execute(request, events::add);

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.NODE_STARTED,
                CanvasExecutionEventType.NODE_FAILED, CanvasExecutionEventType.FAILED),
                events.stream().map(CanvasExecutionEvent::type).toList());
        assertEquals(List.of(0L, 1L, 2L, 3L),
                events.stream().map(CanvasExecutionEvent::sequence).toList());
        assertEquals(nodeId, events.get(2).nodeId());
        assertEquals("expected failure", events.get(2).message());
        assertEquals("expected failure", events.get(3).message());
        assertTrue(events.stream().allMatch(event -> request.executionId().equals(event.executionId())));
    }

    @Test
    void doesNotEmitLifecycleEventsWhenPreflightRejectsRequest() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "input", "input", Map.of(), List.of(
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "invalid-observation",
                List.of(node), List.of());
        CanvasExecutionRequest request = CanvasExecutionRequest.fromEntries(canvas, List.of(UUID.randomUUID()));
        List<CanvasExecutionEvent> events = new ArrayList<>();

        assertThrows(RuntimeException.class,
                () -> new CanvasService(Map.of()).execute(request, events::add));
        assertTrue(events.isEmpty());
    }
}
