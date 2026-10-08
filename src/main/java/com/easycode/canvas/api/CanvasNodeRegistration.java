package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasNodeExecutor;
import com.easycode.canvas.model.CanvasNodeDescriptor;

import java.util.Objects;

/** One executable node type registration joining its shape and behavior. */
public record CanvasNodeRegistration(CanvasNodeDescriptor descriptor, CanvasNodeExecutor executor) {
    public CanvasNodeRegistration {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(executor, "executor");
    }
}
