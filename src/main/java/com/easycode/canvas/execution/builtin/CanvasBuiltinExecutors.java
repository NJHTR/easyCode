package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.execution.CanvasNodeExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small deterministic in-memory node library for smoke tests and local graphs. */
public final class CanvasBuiltinExecutors {
    public static final String CONSTANT = "constant";
    public static final String PRINT = "print";
    public static final String PASSTHROUGH = "passthrough";

    private CanvasBuiltinExecutors() {
    }

    public static Map<String, CanvasNodeExecutor> all() {
        Map<String, CanvasNodeExecutor> executors = new LinkedHashMap<>();
        executors.put(CONSTANT, (node, context) -> {
            if (!node.configuration().containsKey("value")) {
                throw new IllegalArgumentException("constant node requires configuration value");
            }
            context.output("out", node.configuration().get("value"));
        });
        executors.put(PRINT, (node, context) -> context.console(context.input("in")));
        executors.put(PASSTHROUGH, (node, context) -> context.output("out", context.input("in")));
        return Map.copyOf(executors);
    }
}
