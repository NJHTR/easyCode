package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasDefinition;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Describes one synchronous canvas run and its optional explicit entry nodes. */
public record CanvasExecutionRequest(CanvasDefinition canvas, List<UUID> entryNodeIds) {
    public CanvasExecutionRequest {
        Objects.requireNonNull(canvas, "canvas");
        entryNodeIds = entryNodeIds == null ? List.of() : List.copyOf(entryNodeIds);
        if (entryNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entry node ids cannot contain null");
        }
        if (entryNodeIds.stream().distinct().count() != entryNodeIds.size()) {
            throw new IllegalArgumentException("entry node ids cannot contain duplicates");
        }
    }

    public static CanvasExecutionRequest forCanvas(CanvasDefinition canvas) {
        return new CanvasExecutionRequest(canvas, List.of());
    }

    public static CanvasExecutionRequest fromEntries(CanvasDefinition canvas, List<UUID> entryNodeIds) {
        return new CanvasExecutionRequest(canvas, entryNodeIds);
    }
}
