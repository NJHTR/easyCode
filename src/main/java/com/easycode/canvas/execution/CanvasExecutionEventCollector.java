package com.easycode.canvas.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Collects lifecycle events from one synchronous execution for later inspection.
 * The collector is intentionally in-memory and does not provide persistence or
 * realtime delivery semantics.
 */
public final class CanvasExecutionEventCollector implements CanvasExecutionObserver {
    private final List<CanvasExecutionEvent> events = new ArrayList<>();
    private final CanvasExecutionValueSnapshotter snapshotter;
    private UUID executionId;
    private Instant startedAt;
    private Instant completedAt;
    private boolean terminal;
    private UUID failedNodeId;
    private CanvasExecutionFailure failureDetails;
    private String terminalMessage;
    private UUID activeNodeId;
    private Map<UUID, Object> activeNodeInputs = Map.of();
    private UUID debuggerPausedNodeId;
    private Map<UUID, Object> debuggerPausedNodeInputs = Map.of();
    private CanvasExecutionPauseReason debuggerPauseReason;
    private UUID debuggerResumedNodeId;
    private CanvasExecutionEventType lastNodeOutcome;
    private final List<UUID> completedNodeIds = new ArrayList<>();
    private final Map<UUID, Map<UUID, Object>> nodeInputs = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, Object>> nodeOutputs = new LinkedHashMap<>();
    private final Map<UUID, CanvasExecutionStatus> nodeStatuses = new LinkedHashMap<>();
    private final Map<UUID, CanvasExecutionFailure> nodeFailures = new LinkedHashMap<>();
    private final Map<UUID, String> nodeFailureMessages = new LinkedHashMap<>();
    private final List<String> consoleOutput = new ArrayList<>();
    private final Map<UUID, Object> publishedOutputValues = new LinkedHashMap<>();
    private CanvasExecutionStatus terminalStatus;

    public CanvasExecutionEventCollector() {
        this(CanvasExecutionValueSnapshotter.identity());
    }

    public CanvasExecutionEventCollector(CanvasExecutionValueSnapshotter snapshotter) {
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    }

    @Override
    public synchronized void onEvent(CanvasExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (terminal) {
            throw new IllegalStateException("execution event collector already received a terminal event");
        }
        if (events.isEmpty()) {
            if (event.type() != CanvasExecutionEventType.STARTED || event.sequence() != 0L) {
                throw new IllegalArgumentException("first execution event must be STARTED with sequence 0");
            }
            executionId = event.executionId();
        } else {
            if (!executionId.equals(event.executionId())) {
                throw new IllegalArgumentException("execution event belongs to a different execution");
            }
            if (event.sequence() != events.size()) {
                throw new IllegalArgumentException("execution event sequence is not contiguous");
            }
        }
        CanvasExecutionEvent snapshot = event.withValueSnapshotter(snapshotter);
        validateLifecycle(event);
        if (events.isEmpty()) {
            startedAt = snapshot.occurredAt();
        }
        if (event.type() == CanvasExecutionEventType.DEBUGGER_PAUSED) {
            debuggerPausedNodeId = event.nodeId();
            debuggerPausedNodeInputs = snapshot.inputs();
            debuggerPauseReason = snapshot.pauseReason();
        } else if (event.type() == CanvasExecutionEventType.DEBUGGER_RESUMED) {
            debuggerPausedNodeId = null;
            debuggerPausedNodeInputs = Map.of();
            debuggerPauseReason = null;
            debuggerResumedNodeId = event.nodeId();
        }
        if (event.type() == CanvasExecutionEventType.NODE_STARTED) {
            activeNodeId = event.nodeId();
            activeNodeInputs = snapshot.inputs();
            nodeInputs.put(event.nodeId(), snapshot.inputs());
            debuggerResumedNodeId = null;
        } else if (event.type() == CanvasExecutionEventType.NODE_SUCCEEDED
                || event.type() == CanvasExecutionEventType.NODE_FAILED
                || event.type() == CanvasExecutionEventType.NODE_CANCELLED) {
            boolean cancelledPausedNode = event.type() == CanvasExecutionEventType.NODE_CANCELLED
                    && (event.nodeId().equals(debuggerPausedNodeId)
                    || event.nodeId().equals(debuggerResumedNodeId));
            activeNodeId = null;
            activeNodeInputs = Map.of();
            if (cancelledPausedNode) {
                debuggerPausedNodeId = null;
                debuggerPausedNodeInputs = Map.of();
                debuggerPauseReason = null;
                debuggerResumedNodeId = null;
            }
            lastNodeOutcome = event.type();
            if (event.type() == CanvasExecutionEventType.NODE_FAILED) {
                failedNodeId = event.nodeId();
                failureDetails = snapshot.failureDetails();
            }
            if (event.type() == CanvasExecutionEventType.NODE_SUCCEEDED) {
                completedNodeIds.add(event.nodeId());
                publishedOutputValues.putAll(snapshot.outputs());
            }
            nodeOutputs.put(event.nodeId(), snapshot.outputs());
            nodeStatuses.put(event.nodeId(), nodeStatusOf(event.type()));
            if (event.type() == CanvasExecutionEventType.NODE_FAILED) {
                nodeFailureMessages.put(event.nodeId(), snapshot.message());
            }
            if (event.type() == CanvasExecutionEventType.NODE_FAILED
                    && snapshot.failureDetails() != null) {
                nodeFailures.put(event.nodeId(), snapshot.failureDetails());
            }
            consoleOutput.addAll(snapshot.consoleOutput());
        }
        events.add(snapshot);
        terminalStatus = terminalStatusOf(event.type());
        if (terminalStatus != null) {
            completedAt = snapshot.occurredAt();
        }
        if (terminalStatus != null && !event.message().isBlank()) {
            terminalMessage = event.message();
        }
        terminal = terminalStatus != null;
        if (terminal) {
            activeNodeId = null;
            activeNodeInputs = Map.of();
            debuggerPausedNodeId = null;
            debuggerPausedNodeInputs = Map.of();
            debuggerPauseReason = null;
            debuggerResumedNodeId = null;
        }
    }

    private void validateLifecycle(CanvasExecutionEvent event) {
        CanvasExecutionEventType type = event.type();
        if (events.isEmpty()) {
            return;
        }
        if ((lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED && type != CanvasExecutionEventType.FAILED)
                || (lastNodeOutcome == CanvasExecutionEventType.NODE_CANCELLED
                && type != CanvasExecutionEventType.CANCELLED)) {
            throw new IllegalArgumentException("terminal node outcome must be followed by its execution outcome");
        }
        if (debuggerResumedNodeId != null
                && (type != CanvasExecutionEventType.NODE_STARTED
                && (type != CanvasExecutionEventType.NODE_CANCELLED
                || !debuggerResumedNodeId.equals(event.nodeId())))) {
            throw new IllegalArgumentException("resumed debugger node must start or be cancelled before another event");
        }
        switch (type) {
            case STARTED -> throw new IllegalArgumentException("execution can only start once");
            case DEBUGGER_PAUSED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null) {
                    throw new IllegalArgumentException("debugger can pause only between nodes");
                }
                if (nodeStatuses.containsKey(event.nodeId())) {
                    throw new IllegalArgumentException("completed node cannot be paused again");
                }
            }
            case DEBUGGER_RESUMED -> {
                if (debuggerPausedNodeId == null || !event.nodeId().equals(debuggerPausedNodeId)) {
                    throw new IllegalArgumentException("debugger resume must match the paused node");
                }
            }
            case NODE_STARTED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null) {
                    throw new IllegalArgumentException("node execution cannot overlap another node or debugger pause");
                }
                if (debuggerResumedNodeId != null && !debuggerResumedNodeId.equals(event.nodeId())) {
                    throw new IllegalArgumentException("resumed node must match the next node start");
                }
                if (nodeInputs.containsKey(event.nodeId()) || nodeStatuses.containsKey(event.nodeId())) {
                    throw new IllegalArgumentException("node can only start once per execution");
                }
            }
            case NODE_SUCCEEDED, NODE_FAILED, NODE_CANCELLED -> {
                boolean cancelledPausedNode = type == CanvasExecutionEventType.NODE_CANCELLED
                        && (event.nodeId().equals(debuggerPausedNodeId)
                        || event.nodeId().equals(debuggerResumedNodeId));
                if (!event.nodeId().equals(activeNodeId) && !cancelledPausedNode) {
                    throw new IllegalArgumentException("node outcome must match the active node");
                }
            }
            case SUCCEEDED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null
                        || lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED
                        || lastNodeOutcome == CanvasExecutionEventType.NODE_CANCELLED) {
                    throw new IllegalArgumentException("successful terminal event conflicts with execution state");
                }
            }
            case FAILED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null
                        || lastNodeOutcome != CanvasExecutionEventType.NODE_FAILED) {
                    throw new IllegalArgumentException("failed terminal event requires a failed node outcome");
                }
            }
            case CANCELLED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null
                        || lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED) {
                    throw new IllegalArgumentException("cancelled terminal event conflicts with execution state");
                }
            }
        }
    }

    /** Returns an immutable snapshot of all events collected so far. */
    public synchronized List<CanvasExecutionEvent> events() {
        return List.copyOf(events);
    }

    /** Returns accepted events whose sequence is greater than the supplied cursor. */
    public synchronized List<CanvasExecutionEvent> eventsAfter(long sequence) {
        if (sequence < -1L) {
            throw new IllegalArgumentException("event sequence cursor cannot be less than -1");
        }
        return events.stream()
                .filter(event -> event.sequence() > sequence)
                .toList();
    }

    /** Returns the most recently accepted lifecycle event, if execution has started. */
    public synchronized Optional<CanvasExecutionEvent> lastEvent() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(events.size() - 1));
    }

    /** Returns node ids in the order in which they first appeared in lifecycle events. */
    public synchronized List<UUID> observedNodeIds() {
        LinkedHashSet<UUID> nodeIds = new LinkedHashSet<>();
        events.stream()
                .map(CanvasExecutionEvent::nodeId)
                .filter(Objects::nonNull)
                .forEach(nodeIds::add);
        return List.copyOf(nodeIds);
    }

    /** Returns the execution identity after the first STARTED event is collected. */
    public synchronized Optional<UUID> executionId() {
        return Optional.ofNullable(executionId);
    }

    /** Returns the timestamp carried by the STARTED event. */
    public synchronized Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    /** Returns the timestamp carried by the terminal event, if complete. */
    public synchronized Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    /** Returns elapsed wall-clock time from STARTED to now or the terminal event. */
    public synchronized Optional<Duration> elapsed() {
        if (startedAt == null) {
            return Optional.empty();
        }
        Instant end = completedAt == null ? Instant.now() : completedAt;
        Duration duration = Duration.between(startedAt, end);
        return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
    }

    /** Returns one immutable, internally consistent view of the collected state. */
    public synchronized CanvasExecutionObservation observation() {
        return new CanvasExecutionObservation(
                Optional.ofNullable(executionId),
                Optional.ofNullable(startedAt),
                Optional.ofNullable(completedAt),
                elapsed(),
                Optional.ofNullable(activeNodeId),
                activeNodeInputs,
                Optional.ofNullable(debuggerPausedNodeId),
                debuggerPausedNodeInputs,
                nodeStatuses,
                completedNodeIds,
                publishedOutputValues,
                consoleOutput,
                Optional.ofNullable(terminalStatus),
                Optional.ofNullable(failedNodeId),
                Optional.ofNullable(failureDetails),
                Optional.ofNullable(terminalMessage),
                terminal,
                Optional.ofNullable(debuggerPauseReason));
    }

    /** Returns the timestamp at which a node entered execution, if it started. */
    public synchronized Optional<Instant> nodeStartedAt(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(event -> event.type() == CanvasExecutionEventType.NODE_STARTED)
                .filter(event -> nodeId.equals(event.nodeId()))
                .map(CanvasExecutionEvent::occurredAt)
                .findFirst();
    }

    /** Returns the timestamp at which a node reached a terminal outcome, if any. */
    public synchronized Optional<Instant> nodeCompletedAt(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(event -> isNodeTerminal(event.type()))
                .filter(event -> nodeId.equals(event.nodeId()))
                .map(CanvasExecutionEvent::occurredAt)
                .findFirst();
    }

    /** Returns node wall-clock time from its start to its outcome or to now while active. */
    public synchronized Optional<Duration> nodeElapsed(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        Optional<Instant> started = nodeStartedAt(nodeId);
        if (started.isEmpty()) {
            return Optional.empty();
        }
        Instant end = nodeCompletedAt(nodeId).orElseGet(Instant::now);
        Duration duration = Duration.between(started.orElseThrow(), end);
        return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
    }

    /** Returns lifecycle events for one node in their original execution order. */
    public synchronized List<CanvasExecutionEvent> eventsForNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(event -> nodeId.equals(event.nodeId()))
                .toList();
    }

    /** Returns events of one lifecycle type in their original execution order. */
    public synchronized List<CanvasExecutionEvent> eventsOfType(CanvasExecutionEventType type) {
        Objects.requireNonNull(type, "type");
        return events.stream()
                .filter(event -> event.type() == type)
                .toList();
    }

    /** Returns the number of events collected so far. */
    public synchronized int size() {
        return events.size();
    }

    /** Returns whether a terminal success, failure, or cancellation event has been collected. */
    public synchronized boolean isComplete() {
        return terminal;
    }

    /** Returns the node whose executor is currently running, if any. */
    public synchronized Optional<UUID> activeNodeId() {
        return Optional.ofNullable(activeNodeId);
    }

    /** Returns the immutable inputs captured when the active node started. */
    public synchronized Optional<Map<UUID, Object>> activeNodeInputs() {
        return activeNodeId == null ? Optional.empty() : Optional.of(activeNodeInputs);
    }

    /** Returns the immutable input snapshot captured when a node started. */
    public synchronized Optional<Map<UUID, Object>> nodeInputs(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return Optional.ofNullable(nodeInputs.get(nodeId));
    }

    /** Returns the immutable output snapshot captured when a node reached an outcome. */
    public synchronized Optional<Map<UUID, Object>> nodeOutputs(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return Optional.ofNullable(nodeOutputs.get(nodeId));
    }

    /** Returns the terminal status of a node after it reaches an outcome. */
    public synchronized Optional<CanvasExecutionStatus> nodeStatus(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return Optional.ofNullable(nodeStatuses.get(nodeId));
    }

    /** Returns exception diagnostics for a node that failed by throwing. */
    public synchronized Optional<CanvasExecutionFailure> nodeFailureDetails(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return Optional.ofNullable(nodeFailures.get(nodeId));
    }

    /** Returns the failure message for a node that reached the failed outcome. */
    public synchronized Optional<String> nodeFailureMessage(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return Optional.ofNullable(nodeFailureMessages.get(nodeId));
    }

    /** Returns one consistent observation of a node, if that node has been observed. */
    public synchronized Optional<CanvasNodeExecutionObservation> nodeObservation(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        Map<UUID, Object> inputs = nodeInputs.get(nodeId);
        if (inputs == null) {
            inputs = events.stream()
                    .filter(event -> event.type() == CanvasExecutionEventType.DEBUGGER_PAUSED)
                    .filter(event -> nodeId.equals(event.nodeId()))
                    .map(CanvasExecutionEvent::inputs)
                    .findFirst()
                    .orElse(null);
        }
        if (inputs == null && !nodeStatuses.containsKey(nodeId)) {
            return Optional.empty();
        }
        return Optional.of(new CanvasNodeExecutionObservation(
                nodeId,
                nodeStartedAt(nodeId),
                nodeCompletedAt(nodeId),
                nodeElapsed(nodeId),
                inputs == null ? Map.of() : inputs,
                Optional.ofNullable(nodeOutputs.get(nodeId)),
                consoleOutputForNode(nodeId),
                Optional.ofNullable(nodeStatuses.get(nodeId)),
                Optional.ofNullable(nodeFailureMessages.get(nodeId)),
                Optional.ofNullable(nodeFailures.get(nodeId))));
    }

    /** Returns immutable node observations in their first observed execution order. */
    public synchronized Map<UUID, CanvasNodeExecutionObservation> nodeObservations() {
        Map<UUID, CanvasNodeExecutionObservation> observations = new LinkedHashMap<>();
        for (CanvasExecutionEvent event : events) {
            if (event.nodeId() != null) {
                nodeObservation(event.nodeId()).ifPresent(observation ->
                        observations.putIfAbsent(event.nodeId(), observation));
            }
        }
        return Collections.unmodifiableMap(observations);
    }

    /** Returns the node at which execution is currently paused, if any. */
    public synchronized Optional<UUID> pausedNodeId() {
        return Optional.ofNullable(debuggerPausedNodeId);
    }

    /** Returns the immutable inputs captured at the current debugger pause. */
    public synchronized Optional<Map<UUID, Object>> pausedNodeInputs() {
        return debuggerPausedNodeId == null
                ? Optional.empty()
                : Optional.of(debuggerPausedNodeInputs);
    }

    /** Returns why the observed execution is paused, if the event includes that detail. */
    public synchronized Optional<CanvasExecutionPauseReason> pausedReason() {
        return Optional.ofNullable(debuggerPauseReason);
    }

    /** Returns node IDs that have completed successfully in execution order. */
    public synchronized List<UUID> completedNodeIds() {
        return List.copyOf(completedNodeIds);
    }

    /** Returns console lines observed from completed, failed, or cancelled nodes. */
    public synchronized List<String> consoleOutput() {
        return List.copyOf(consoleOutput);
    }

    /** Returns values published by successfully completed nodes so far. */
    public synchronized Map<UUID, Object> publishedOutputValues() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(publishedOutputValues));
    }

    /** Looks up one published output while preserving an explicit null value. */
    public synchronized CanvasValueLookup publishedOutput(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return publishedOutputValues.containsKey(portId)
                ? CanvasValueLookup.present(publishedOutputValues.get(portId))
                : CanvasValueLookup.missing();
    }

    /** Returns console lines emitted by one node in lifecycle order. */
    public synchronized List<String> consoleOutputForNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(event -> nodeId.equals(event.nodeId()))
                .filter(event -> event.type() == CanvasExecutionEventType.NODE_SUCCEEDED
                        || event.type() == CanvasExecutionEventType.NODE_FAILED
                        || event.type() == CanvasExecutionEventType.NODE_CANCELLED)
                .flatMap(event -> event.consoleOutput().stream())
                .toList();
    }

    /** Returns the terminal status once execution has completed. */
    public synchronized Optional<CanvasExecutionStatus> terminalStatus() {
        return Optional.ofNullable(terminalStatus);
    }

    /** Returns the node that produced the terminal failure, if any. */
    public synchronized Optional<UUID> failedNodeId() {
        return Optional.ofNullable(failedNodeId);
    }

    /** Returns exception diagnostics from the failed node, when an exception was thrown. */
    public synchronized Optional<CanvasExecutionFailure> failureDetails() {
        return Optional.ofNullable(failureDetails);
    }

    /** Returns the terminal failure or cancellation message, if one was emitted. */
    public synchronized Optional<String> terminalMessage() {
        return Optional.ofNullable(terminalMessage);
    }

    private static CanvasExecutionStatus terminalStatusOf(CanvasExecutionEventType type) {
        return switch (type) {
            case SUCCEEDED -> CanvasExecutionStatus.SUCCEEDED;
            case FAILED -> CanvasExecutionStatus.FAILED;
            case CANCELLED -> CanvasExecutionStatus.CANCELLED;
            default -> null;
        };
    }

    private static boolean isNodeTerminal(CanvasExecutionEventType type) {
        return type == CanvasExecutionEventType.NODE_SUCCEEDED
                || type == CanvasExecutionEventType.NODE_FAILED
                || type == CanvasExecutionEventType.NODE_CANCELLED;
    }

    private static CanvasExecutionStatus nodeStatusOf(CanvasExecutionEventType type) {
        return switch (type) {
            case NODE_SUCCEEDED -> CanvasExecutionStatus.SUCCEEDED;
            case NODE_FAILED -> CanvasExecutionStatus.FAILED;
            case NODE_CANCELLED -> CanvasExecutionStatus.CANCELLED;
            default -> throw new IllegalArgumentException("not a node terminal event: " + type);
        };
    }
}
