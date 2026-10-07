package com.easycode.canvas.plan;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, structural order for visiting the nodes of a canvas graph.
 *
 * <p>This is a plan only; it does not execute nodes or carry runtime values.</p>
 */
public record CanvasExecutionPlan(UUID canvasId, List<UUID> orderedNodeIds) {
    public CanvasExecutionPlan {
        Objects.requireNonNull(canvasId, "canvasId");
        orderedNodeIds = orderedNodeIds == null ? List.of() : List.copyOf(orderedNodeIds);
        if (orderedNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ordered node ids cannot contain null");
        }
    }
}
