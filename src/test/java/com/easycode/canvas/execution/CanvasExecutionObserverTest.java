package com.easycode.canvas.execution;

import com.easycode.canvas.api.CanvasApplication;
import com.easycode.canvas.api.CanvasService;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        assertTrue(events.stream().allMatch(event -> event.occurredAt() != null));
        assertTrue(events.get(0).occurredAt().compareTo(events.get(5).occurredAt()) <= 0);
        assertEquals(first.nodeId(), events.get(1).nodeId());
        assertEquals(first.nodeId(), events.get(2).nodeId());
        assertEquals(second.nodeId(), events.get(3).nodeId());
        assertEquals(second.nodeId(), events.get(4).nodeId());
        assertEquals(Map.of(), events.get(1).inputs());
        assertEquals(Map.of(), events.get(2).inputs());
        assertEquals(Map.of(first.ports().get(0).portId(), "one"), events.get(2).outputs());
        assertEquals(Map.of(second.ports().get(0).portId(), "one"), events.get(3).inputs());
        assertEquals(Map.of(), events.get(1).outputs());
        assertEquals(List.of("one"), events.get(4).consoleOutput());
        assertEquals(Map.of(), events.get(4).outputs());
        assertThrows(UnsupportedOperationException.class,
                () -> events.get(3).inputs().put(UUID.randomUUID(), "mutated"));
        assertThrows(UnsupportedOperationException.class,
                () -> events.get(4).consoleOutput().add("mutated"));
        assertTrue(events.stream().allMatch(event -> request.executionId().equals(event.executionId())));
        assertEquals(request.executionId(), result.executionId());
    }

    @Test
    void observesFailedNodeAndTerminalFailure() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "broken", "broken", Map.of(), List.of(
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "observed-failure",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "broken", (ignoredNode, context) -> {
                    context.console("before failure");
                    context.output(outputPortId, "partial");
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
        assertEquals(Map.of(), events.get(2).inputs());
        assertEquals(List.of("before failure"), events.get(2).consoleOutput());
        assertEquals(Map.of(outputPortId, "partial"), events.get(2).outputs());
        assertTrue(events.get(1).occurredAt().compareTo(events.get(2).occurredAt()) <= 0);
        assertTrue(events.get(2).occurredAt().compareTo(events.get(3).occurredAt()) <= 0);
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

    @Test
    void explicitOccurredAtIsPreserved() {
        Instant occurredAt = Instant.parse("2026-01-02T03:04:05Z");
        CanvasExecutionEvent event = new CanvasExecutionEvent(UUID.randomUUID(),
                CanvasExecutionEventType.STARTED, null, "", 0L,
                Map.of(), Map.of(), List.of(), occurredAt);

        assertEquals(occurredAt, event.occurredAt());
    }

    @Test
    void collectorKeepsAnImmutableExecutionSnapshot() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode constant = application.createNode(CanvasBuiltinExecutors.CONSTANT, "constant",
                Map.of("value", "collected"));
        CanvasDefinition canvas = application.newCanvas("collected-events")
                .addNode(constant)
                .build();
        CanvasExecutionRequest request = CanvasExecutionRequest.forCanvas(canvas);
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();

        CanvasExecutionResult result = application.execute(request, collector);

        assertEquals(4, collector.size());
        assertEquals(List.of(CanvasExecutionEventType.STARTED,
                        CanvasExecutionEventType.NODE_STARTED,
                        CanvasExecutionEventType.NODE_SUCCEEDED,
                        CanvasExecutionEventType.SUCCEEDED),
                collector.events().stream().map(CanvasExecutionEvent::type).toList());
        assertEquals(request.executionId(), collector.events().get(0).executionId());
        assertEquals(result.executionId(), collector.events().get(0).executionId());
        assertEquals(List.of(CanvasExecutionEventType.NODE_STARTED,
                        CanvasExecutionEventType.NODE_SUCCEEDED),
                collector.eventsForNode(constant.nodeId()).stream()
                        .map(CanvasExecutionEvent::type).toList());
        assertEquals(1, collector.eventsOfType(CanvasExecutionEventType.SUCCEEDED).size());
        assertThrows(UnsupportedOperationException.class,
                () -> collector.eventsForNode(constant.nodeId()).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> collector.events().clear());
    }
}
