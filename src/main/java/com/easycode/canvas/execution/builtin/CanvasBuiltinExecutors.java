package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.execution.CanvasNodeExecutor;

import java.util.Map;

/** Small deterministic in-memory node library for smoke tests and local graphs. */
public final class CanvasBuiltinExecutors {
    public static final String CONSTANT = "constant";
    public static final String PRINT = "print";
    public static final String PASSTHROUGH = "passthrough";
    public static final String ADD = "add";

    private CanvasBuiltinExecutors() {
    }

    public static Map<String, CanvasNodeExecutor> all() {
        return CanvasBuiltinLibrary.registry().executors();
    }
}
