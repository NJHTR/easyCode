package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.execution.CanvasNodeExecutor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
        Map<String, CanvasNodeExecutor> executors = new LinkedHashMap<>();
        executors.put(CONSTANT, (node, context) -> {
            if (!node.configuration().containsKey("value")) {
                throw new IllegalArgumentException("constant node requires configuration value");
            }
            context.output("out", node.configuration().get("value"));
        });
        executors.put(PRINT, (node, context) -> context.console(context.input("in")));
        executors.put(PASSTHROUGH, (node, context) -> context.output("out", context.input("in")));
        executors.put(ADD, (node, context) -> {
            BigDecimal left = numberInput(context, "left");
            BigDecimal right = numberInput(context, "right");
            context.output("out", left.add(right).stripTrailingZeros());
        });
        return Map.copyOf(executors);
    }

    private static BigDecimal numberInput(com.easycode.canvas.execution.CanvasNodeExecutionContext context,
                                          String portName) {
        Object value = context.input(portName);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("add node requires a numeric input on port: " + portName);
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("add node received an invalid numeric input on port: " + portName,
                    exception);
        }
    }
}
