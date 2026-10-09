package com.easycode.canvas.execution;

import com.easycode.canvas.api.CanvasApplication;
import com.easycode.canvas.api.CanvasService;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(IllegalStateException.class.getName(), events.get(2).failureDetails().exceptionType());
        assertEquals("expected failure", events.get(2).failureDetails().message());
        assertTrue(events.get(2).failureDetails().stackTrace().contains("CanvasExecutionObserverTest"));
        CanvasExecutionFailure traceFailure = result.trace(nodeId).orElseThrow().failureDetails();
        assertEquals(events.get(2).failureDetails(), traceFailure);
        CanvasExecutionResult unobservedResult = service.execute(request);
        assertEquals(IllegalStateException.class.getName(),
                unobservedResult.trace(nodeId).orElseThrow().failureDetails().exceptionType());
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
        assertEquals(true, collector.isComplete());
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
        assertThrows(IllegalStateException.class,
                () -> collector.onEvent(collector.events().get(0)));
    }

    @Test
    void collectorRejectsBrokenEventSequences() {
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();
        CanvasExecutionEvent started = new CanvasExecutionEvent(UUID.randomUUID(),
                CanvasExecutionEventType.STARTED, null, "", 0L);

        assertThrows(IllegalArgumentException.class, () -> collector.onEvent(
                new CanvasExecutionEvent(UUID.randomUUID(), CanvasExecutionEventType.NODE_STARTED,
                        UUID.randomUUID(), "", 0L)));
        collector.onEvent(started);
        assertThrows(IllegalArgumentException.class, () -> collector.onEvent(
                new CanvasExecutionEvent(started.executionId(), CanvasExecutionEventType.SUCCEEDED,
                        null, "", 2L)));
        assertThrows(IllegalArgumentException.class, () -> collector.onEvent(
                new CanvasExecutionEvent(UUID.randomUUID(), CanvasExecutionEventType.SUCCEEDED,
                        null, "", 1L)));
        assertEquals(1, collector.size());
        assertEquals(false, collector.isComplete());
    }

    @Test
    void eventSnapshotCopiesNestedCollectionsAndArrays() {
        UUID portId = UUID.randomUUID();
        List<Object> originalList = new ArrayList<>(List.of("before"));
        Map<String, Object> originalMap = new LinkedHashMap<>();
        originalMap.put("items", originalList);
        Object[] originalArray = new Object[]{"array-before"};
        CanvasExecutionEvent event = new CanvasExecutionEvent(UUID.randomUUID(),
                CanvasExecutionEventType.NODE_SUCCEEDED, UUID.randomUUID(), "", 1L,
                Map.of(), Map.of(portId, List.of(originalMap, originalArray)), List.of());

        originalList.add("after");
        originalMap.put("later", true);
        originalArray[0] = "array-after";

        List<?> output = (List<?>) event.outputs().get(portId);
        Map<?, ?> mapSnapshot = (Map<?, ?>) output.get(0);
        List<?> listSnapshot = (List<?>) mapSnapshot.get("items");
        List<?> arraySnapshot = (List<?>) output.get(1);
        assertEquals(List.of("before"), listSnapshot);
        assertEquals(false, mapSnapshot.containsKey("later"));
        assertEquals(List.of("array-before"), arraySnapshot);
        assertThrows(UnsupportedOperationException.class, listSnapshot::clear);
    }

    @Test
    void eventSnapshotPreservesCyclesWithoutExposingMutableContainers() {
        UUID portId = UUID.randomUUID();
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        CanvasExecutionEvent event = new CanvasExecutionEvent(UUID.randomUUID(),
                CanvasExecutionEventType.NODE_SUCCEEDED, UUID.randomUUID(), "", 1L,
                Map.of(), Map.of(portId, cyclic), List.of());

        List<?> snapshot = (List<?>) event.outputs().get(portId);

        assertSame(snapshot, snapshot.get(0));
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void collectorCanSnapshotCustomMutableValues() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        MutableValue value = new MutableValue("before");
        CanvasNode node = new CanvasNode(nodeId, "custom", "custom", Map.of(), List.of(
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "custom-snapshot",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "custom", (ignoredNode, context) -> context.output(outputPortId, value)));
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector(candidate ->
                candidate instanceof MutableValue mutable
                        ? Map.of("value", mutable.value)
                        : candidate);

        CanvasExecutionResult result = service.execute(CanvasExecutionRequest.forCanvas(canvas), collector);
        assertSame(value, result.output(outputPortId).value());
        value.value = "after";

        CanvasExecutionEvent succeeded = collector.eventsOfType(CanvasExecutionEventType.NODE_SUCCEEDED).get(0);
        Map<?, ?> outputSnapshot = (Map<?, ?>) succeeded.outputs().get(outputPortId);
        assertEquals("before", outputSnapshot.get("value"));
        assertThrows(UnsupportedOperationException.class, outputSnapshot::clear);
    }

    @Test
    void serviceCanProjectCustomValuesInTracesWithoutChangingRuntimeOutputs() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        MutableValue value = new MutableValue("before");
        CanvasNode node = new CanvasNode(nodeId, "custom", "custom", Map.of(), List.of(
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "custom-trace-snapshot",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "custom", (ignoredNode, context) -> context.output(outputPortId, value)),
                candidate -> candidate instanceof MutableValue mutable
                        ? Map.of("value", mutable.value)
                        : candidate);

        CanvasExecutionResult result = service.execute(CanvasExecutionRequest.forCanvas(canvas));
        value.value = "after";

        assertSame(value, result.output(outputPortId).value());
        Map<?, ?> traceSnapshot = (Map<?, ?>) result.trace(nodeId).orElseThrow().outputs().get(outputPortId);
        assertEquals("before", traceSnapshot.get("value"));
        assertThrows(UnsupportedOperationException.class, traceSnapshot::clear);
    }

    @Test
    void cancellationBeforeExecutionEmitsTerminalCancellation() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode constant = application.createNode(CanvasBuiltinExecutors.CONSTANT, "constant",
                Map.of("value", "never-run"));
        CanvasDefinition canvas = application.newCanvas("cancel-before-start")
                .addNode(constant)
                .build();
        CanvasExecutionRequest request = CanvasExecutionRequest.forCanvas(canvas);
        CanvasExecutionCancellationToken cancellation = new CanvasExecutionCancellationToken();
        cancellation.cancel();
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();

        CanvasExecutionResult result = application.execute(request, collector, cancellation);

        assertEquals(CanvasExecutionStatus.CANCELLED, result.status());
        assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.CANCELLED),
                collector.events().stream().map(CanvasExecutionEvent::type).toList());
        assertTrue(collector.isComplete());
        assertEquals(List.of(), result.completedNodeIds());
    }

    @Test
    void nodeCanCooperativelyCancelAndKeepPartialDiagnostics() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "cooperative", "cooperative", Map.of(), List.of(
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "cancel-in-node",
                List.of(node), List.of());
        CanvasExecutionCancellationToken cancellation = new CanvasExecutionCancellationToken();
        CanvasService service = new CanvasService(Map.of(
                "cooperative", (ignoredNode, context) -> {
                    context.console("partial log");
                    context.output(outputPortId, "partial value");
                    cancellation.cancel();
                    context.throwIfCancellationRequested();
                }));
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();

        CanvasExecutionResult result = service.execute(CanvasExecutionRequest.forCanvas(canvas),
                collector, cancellation);

        assertEquals(CanvasExecutionStatus.CANCELLED, result.status());
        assertEquals(null, result.failedNodeId());
        assertEquals("partial value", result.trace(nodeId).orElseThrow().outputs().get(outputPortId));
        assertEquals(CanvasExecutionStatus.CANCELLED, result.trace(nodeId).orElseThrow().status());
        assertEquals(Map.of(), result.outputValues());
        assertEquals(List.of("partial log"), result.consoleOutput());
        assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.NODE_STARTED,
                        CanvasExecutionEventType.NODE_CANCELLED, CanvasExecutionEventType.CANCELLED),
                collector.events().stream().map(CanvasExecutionEvent::type).toList());
        assertEquals("partial value", collector.events().get(2).outputs().get(outputPortId));
        assertTrue(collector.isComplete());
    }

    @Test
    void executorCancellationExceptionWithoutRequestedCancellationIsFailure() {
        UUID nodeId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "aborted", "aborted", Map.of(), List.of());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "executor-aborted",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "aborted", (ignoredNode, context) -> {
                    throw new CancellationException("executor operation aborted");
                }));
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();

        CanvasExecutionResult result = service.execute(CanvasExecutionRequest.forCanvas(canvas), collector,
                new CanvasExecutionCancellationToken());

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals("executor operation aborted", result.failureMessage());
        assertEquals(CanvasExecutionStatus.FAILED, result.trace(nodeId).orElseThrow().status());
        assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.NODE_STARTED,
                        CanvasExecutionEventType.NODE_FAILED, CanvasExecutionEventType.FAILED),
                collector.events().stream().map(CanvasExecutionEvent::type).toList());
    }

    @Test
    void debuggerSupportsBreakpointStepAndResume() throws Exception {
        List<UUID> executed = java.util.Collections.synchronizedList(new ArrayList<>());
        CanvasNode first = bareNode("first");
        CanvasNode second = bareNode("second");
        CanvasNode third = bareNode("third");
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "debug-step",
                List.of(first, second, third), List.of());
        CanvasService service = new CanvasService(Map.of(
                "debug-node", (node, context) -> executed.add(node.nodeId())));
        CanvasExecutionDebugger debugger = new CanvasExecutionDebugger();
        debugger.addBreakpoint(second.nodeId());
        CanvasExecutionCancellationToken cancellation = new CanvasExecutionCancellationToken();
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CanvasExecutionResult> result = executor.submit(() -> service.executeDebuggable(
                    CanvasExecutionRequest.forCanvas(canvas), collector,
                    cancellation, debugger));

            assertTrue(debugger.awaitPaused(Duration.ofSeconds(5)));
            assertEquals(second.nodeId(), debugger.pausedNodeId());
            assertEquals(List.of(first.nodeId()), List.copyOf(executed));
            assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.NODE_STARTED,
                            CanvasExecutionEventType.NODE_SUCCEEDED, CanvasExecutionEventType.DEBUGGER_PAUSED),
                    collector.events().stream().map(CanvasExecutionEvent::type).toList());

            debugger.step();
            assertTrue(debugger.awaitPaused(Duration.ofSeconds(5)));
            assertEquals(third.nodeId(), debugger.pausedNodeId());
            assertEquals(List.of(first.nodeId(), second.nodeId()), List.copyOf(executed));
            assertEquals(List.of(CanvasExecutionEventType.STARTED,
                            CanvasExecutionEventType.NODE_STARTED, CanvasExecutionEventType.NODE_SUCCEEDED,
                            CanvasExecutionEventType.DEBUGGER_PAUSED, CanvasExecutionEventType.DEBUGGER_RESUMED,
                            CanvasExecutionEventType.NODE_STARTED, CanvasExecutionEventType.NODE_SUCCEEDED,
                            CanvasExecutionEventType.DEBUGGER_PAUSED),
                    collector.events().stream().map(CanvasExecutionEvent::type).toList());

            debugger.resume();
            assertEquals(CanvasExecutionStatus.SUCCEEDED,
                    result.get(5, TimeUnit.SECONDS).status());
            assertEquals(List.of(first.nodeId(), second.nodeId(), third.nodeId()), List.copyOf(executed));
            assertTrue(collector.isComplete());
        } finally {
            debugger.resume();
            cancellation.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationReleasesExecutionPausedAtBreakpoint() throws Exception {
        CanvasNode node = bareNode("paused");
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "debug-cancel",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of("debug-node", (ignored, context) -> { }));
        CanvasExecutionDebugger debugger = new CanvasExecutionDebugger();
        debugger.addBreakpoint(node.nodeId());
        CanvasExecutionCancellationToken cancellation = new CanvasExecutionCancellationToken();
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CanvasExecutionResult> result = executor.submit(() -> service.executeDebuggable(
                    CanvasExecutionRequest.forCanvas(canvas), collector,
                    cancellation, debugger));

            assertTrue(debugger.awaitPaused(Duration.ofSeconds(5)));
            cancellation.cancel();

            assertEquals(CanvasExecutionStatus.CANCELLED,
                    result.get(5, TimeUnit.SECONDS).status());
            assertEquals(List.of(CanvasExecutionEventType.STARTED, CanvasExecutionEventType.DEBUGGER_PAUSED,
                            CanvasExecutionEventType.CANCELLED),
                    collector.events().stream().map(CanvasExecutionEvent::type).toList());
            assertTrue(collector.isComplete());
        } finally {
            debugger.resume();
            cancellation.cancel();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void debuggerRejectsBreakpointOutsideExecutionBeforeStarting() {
        CanvasNode node = bareNode("node");
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "invalid-breakpoint",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of("debug-node", (ignored, context) -> { }));
        CanvasExecutionDebugger debugger = new CanvasExecutionDebugger();
        debugger.addBreakpoint(UUID.randomUUID());
        List<CanvasExecutionEvent> events = new ArrayList<>();

        assertThrows(IllegalArgumentException.class, () -> service.executeDebuggable(
                CanvasExecutionRequest.forCanvas(canvas), events::add,
                new CanvasExecutionCancellationToken(), debugger));
        assertTrue(events.isEmpty());
    }

    @Test
    void collectorRejectsDebuggerResumeWithoutMatchingPause() {
        CanvasExecutionEventCollector collector = new CanvasExecutionEventCollector();
        UUID executionId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        collector.onEvent(new CanvasExecutionEvent(executionId, CanvasExecutionEventType.STARTED,
                null, "", 0L));

        assertThrows(IllegalArgumentException.class, () -> collector.onEvent(new CanvasExecutionEvent(
                executionId, CanvasExecutionEventType.DEBUGGER_RESUMED, nodeId, "", 1L)));
        assertEquals(1, collector.size());
    }

    @Test
    void collectorRequiresNodeLifecyclePairsAndMatchingTerminalState() {
        UUID executionId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        CanvasExecutionEventCollector missingStart = new CanvasExecutionEventCollector();
        missingStart.onEvent(new CanvasExecutionEvent(executionId, CanvasExecutionEventType.STARTED,
                null, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> missingStart.onEvent(new CanvasExecutionEvent(
                executionId, CanvasExecutionEventType.NODE_SUCCEEDED, nodeId, "", 1L)));
        assertEquals(1, missingStart.size());

        CanvasExecutionEventCollector activeNode = new CanvasExecutionEventCollector();
        activeNode.onEvent(new CanvasExecutionEvent(executionId, CanvasExecutionEventType.STARTED,
                null, "", 0L));
        activeNode.onEvent(new CanvasExecutionEvent(executionId, CanvasExecutionEventType.NODE_STARTED,
                nodeId, "", 1L));
        assertThrows(IllegalArgumentException.class, () -> activeNode.onEvent(new CanvasExecutionEvent(
                executionId, CanvasExecutionEventType.SUCCEEDED, null, "", 2L)));
        assertEquals(2, activeNode.size());

        CanvasExecutionEventCollector failedWithoutNodeFailure = new CanvasExecutionEventCollector();
        failedWithoutNodeFailure.onEvent(new CanvasExecutionEvent(executionId,
                CanvasExecutionEventType.STARTED, null, "", 0L));
        assertThrows(IllegalArgumentException.class, () -> failedWithoutNodeFailure.onEvent(
                new CanvasExecutionEvent(executionId, CanvasExecutionEventType.FAILED,
                        null, "failure", 1L)));
        assertEquals(1, failedWithoutNodeFailure.size());
    }

    private static CanvasNode bareNode(String name) {
        return new CanvasNode(UUID.randomUUID(), name, "debug-node", Map.of(), List.of());
    }

    private static final class MutableValue {
        private String value;

        private MutableValue(String value) {
            this.value = value;
        }
    }
}
