package com.easycode.canvas.plan;

import com.easycode.canvas.exception.CanvasValidationException;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.validation.CanvasGraphValidator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Builds a deterministic topological plan from a validated canvas graph. */
public final class CanvasExecutionPlanner {
    private final CanvasGraphValidator validator;

    public CanvasExecutionPlanner() {
        this(new CanvasGraphValidator());
    }

    public CanvasExecutionPlanner(CanvasGraphValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Validates the graph and returns its node order. Nodes that are ready at
     * the same time retain their declaration order in the canvas.
     */
    public CanvasExecutionPlan plan(CanvasDefinition canvas) {
        Objects.requireNonNull(canvas, "canvas");
        validator.validate(canvas);

        return planValidated(canvas, Set.of());
    }

    /**
     * Validates and plans either the complete graph or the subgraph reachable
     * from the supplied explicit entry nodes.
     */
    public CanvasExecutionPlan plan(CanvasDefinition canvas, Set<UUID> entryNodeIds) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(entryNodeIds, "entryNodeIds");
        if (entryNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entry node ids cannot contain null");
        }
        validator.validate(canvas);
        if (entryNodeIds.isEmpty()) {
            return planValidated(canvas, Set.of());
        }

        Map<UUID, CanvasNode> nodes = indexNodes(canvas.nodes());
        Map<UUID, Set<UUID>> outgoing = outgoingNodes(canvas.connections());
        Map<UUID, Set<UUID>> incoming = incomingNodes(canvas.connections());
        for (UUID entryNodeId : entryNodeIds) {
            if (!nodes.containsKey(entryNodeId)) {
                throw new CanvasValidationException("entry node does not exist in canvas: " + entryNodeId);
            }
            if (!incoming.getOrDefault(entryNodeId, Set.of()).isEmpty()) {
                throw new CanvasValidationException("entry node must not have incoming connections: "
                        + entryNodeId);
            }
        }

        Set<UUID> reachable = new HashSet<>();
        java.util.ArrayDeque<UUID> pending = new java.util.ArrayDeque<>(entryNodeIds);
        while (!pending.isEmpty()) {
            UUID nodeId = pending.removeFirst();
            if (!reachable.add(nodeId)) {
                continue;
            }
            pending.addAll(outgoing.getOrDefault(nodeId, Set.of()));
        }
        for (UUID nodeId : reachable) {
            for (UUID predecessor : incoming.getOrDefault(nodeId, Set.of())) {
                if (!reachable.contains(predecessor)) {
                    throw new CanvasValidationException("reachable node depends on excluded entry path: "
                            + nodeId);
                }
            }
        }
        return planValidated(canvas, reachable);
    }

    private CanvasExecutionPlan planValidated(CanvasDefinition canvas, Set<UUID> scope) {

        List<CanvasNode> nodes = canvas.nodes();
        if (!scope.isEmpty()) {
            nodes = nodes.stream().filter(node -> scope.contains(node.nodeId())).toList();
        }
        Map<UUID, Integer> declarationOrder = declarationOrder(nodes);
        Map<UUID, Set<UUID>> outgoing = new LinkedHashMap<>();
        Map<UUID, Integer> incomingCounts = new LinkedHashMap<>();
        for (CanvasNode node : nodes) {
            outgoing.put(node.nodeId(), new LinkedHashSet<>());
            incomingCounts.put(node.nodeId(), 0);
        }

        for (CanvasConnection connection : canvas.connections()) {
            if (!outgoing.containsKey(connection.fromNodeId())
                    || !outgoing.containsKey(connection.toNodeId())) {
                continue;
            }
            if (outgoing.get(connection.fromNodeId()).add(connection.toNodeId())) {
                incomingCounts.computeIfPresent(connection.toNodeId(),
                        (ignored, count) -> count + 1);
            }
        }

        PriorityQueue<UUID> ready = new PriorityQueue<>(Comparator
                .comparingInt(declarationOrder::get));
        incomingCounts.forEach((nodeId, count) -> {
            if (count == 0) {
                ready.add(nodeId);
            }
        });

        List<UUID> orderedNodeIds = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            UUID nodeId = ready.remove();
            orderedNodeIds.add(nodeId);
            for (UUID successor : outgoing.get(nodeId)) {
                int remaining = incomingCounts.computeIfPresent(successor,
                        (ignored, count) -> count - 1);
                if (remaining == 0) {
                    ready.add(successor);
                }
            }
        }

        if (orderedNodeIds.size() != nodes.size()) {
            Set<UUID> unresolved = new HashSet<>(incomingCounts.keySet());
            unresolved.removeAll(orderedNodeIds);
            throw new CanvasValidationException("canvas graph contains a cycle involving nodes: " + unresolved);
        }
        return new CanvasExecutionPlan(canvas.canvasId(), orderedNodeIds);
    }

    private static Map<UUID, CanvasNode> indexNodes(List<CanvasNode> nodes) {
        Map<UUID, CanvasNode> indexed = new HashMap<>();
        nodes.forEach(node -> indexed.put(node.nodeId(), node));
        return indexed;
    }

    private static Map<UUID, Set<UUID>> outgoingNodes(List<CanvasConnection> connections) {
        Map<UUID, Set<UUID>> outgoing = new HashMap<>();
        for (CanvasConnection connection : connections) {
            outgoing.computeIfAbsent(connection.fromNodeId(), ignored -> new LinkedHashSet<>())
                    .add(connection.toNodeId());
        }
        return outgoing;
    }

    private static Map<UUID, Set<UUID>> incomingNodes(List<CanvasConnection> connections) {
        Map<UUID, Set<UUID>> incoming = new HashMap<>();
        for (CanvasConnection connection : connections) {
            incoming.computeIfAbsent(connection.toNodeId(), ignored -> new LinkedHashSet<>())
                    .add(connection.fromNodeId());
        }
        return incoming;
    }

    private static Map<UUID, Integer> declarationOrder(List<CanvasNode> nodes) {
        Map<UUID, Integer> order = new HashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            order.put(nodes.get(index).nodeId(), index);
        }
        return order;
    }
}
