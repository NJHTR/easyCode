package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.api.CanvasNodeCatalog;
import com.easycode.canvas.model.CanvasNodeDescriptor;
import com.easycode.canvas.model.CanvasPortDescriptor;
import com.easycode.canvas.model.CanvasPortDirection;

import java.util.List;

/** Descriptions for the small built-in node library. */
public final class CanvasBuiltinNodes {
    private static final CanvasNodeCatalog CATALOG = new CanvasNodeCatalog(List.of(
            new CanvasNodeDescriptor(CanvasBuiltinExecutors.CONSTANT, "Constant", "Publishes a configured value.",
                    List.of(new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT))),
            new CanvasNodeDescriptor(CanvasBuiltinExecutors.PRINT, "Print", "Writes one input to canvas output.",
                    List.of(new CanvasPortDescriptor("in", CanvasPortDirection.INPUT))),
            new CanvasNodeDescriptor(CanvasBuiltinExecutors.PASSTHROUGH, "Passthrough",
                    "Copies one input to one output.", List.of(
                            new CanvasPortDescriptor("in", CanvasPortDirection.INPUT),
                            new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT))),
            new CanvasNodeDescriptor(CanvasBuiltinExecutors.ADD, "Add", "Adds two numeric inputs.", List.of(
                    new CanvasPortDescriptor("left", CanvasPortDirection.INPUT),
                    new CanvasPortDescriptor("right", CanvasPortDirection.INPUT),
                    new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT)))
    ));

    private CanvasBuiltinNodes() {
    }

    public static CanvasNodeCatalog catalog() {
        return CATALOG;
    }
}
