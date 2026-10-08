package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.api.CanvasNodeRegistration;
import com.easycode.canvas.api.CanvasNodeRegistry;
import com.easycode.canvas.execution.CanvasNodeExecutionContext;
import com.easycode.canvas.model.CanvasNodeDescriptor;
import com.easycode.canvas.model.CanvasPortDescriptor;
import com.easycode.canvas.model.CanvasPortDirection;

import java.math.BigDecimal;
import java.util.List;

/** Canonical executable registration for the small built-in node library. */
public final class CanvasBuiltinLibrary {
    private static final CanvasNodeRegistry REGISTRY = new CanvasNodeRegistry(List.of(
            new CanvasNodeRegistration(
                    new CanvasNodeDescriptor(CanvasBuiltinExecutors.CONSTANT, "Constant",
                            "Publishes a configured value.",
                            List.of(new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT))),
                    (node, context) -> {
                        if (!node.configuration().containsKey("value")) {
                            throw new IllegalArgumentException("constant node requires configuration value");
                        }
                        context.output("out", node.configuration().get("value"));
                    }),
            new CanvasNodeRegistration(
                    new CanvasNodeDescriptor(CanvasBuiltinExecutors.PRINT, "Print",
                            "Writes one input to canvas output.",
                            List.of(new CanvasPortDescriptor("in", CanvasPortDirection.INPUT))),
                    (node, context) -> context.console(requiredInput(context, "in"))),
            new CanvasNodeRegistration(
                    new CanvasNodeDescriptor(CanvasBuiltinExecutors.PASSTHROUGH, "Passthrough",
                            "Copies one input to one output.", List.of(
                                    new CanvasPortDescriptor("in", CanvasPortDirection.INPUT),
                                    new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT))),
                    (node, context) -> context.output("out", requiredInput(context, "in"))),
            new CanvasNodeRegistration(
                    new CanvasNodeDescriptor(CanvasBuiltinExecutors.ADD, "Add", "Adds two numeric inputs.", List.of(
                            new CanvasPortDescriptor("left", CanvasPortDirection.INPUT),
                            new CanvasPortDescriptor("right", CanvasPortDirection.INPUT),
                            new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT))),
                    (node, context) -> {
                        BigDecimal left = numberInput(context, "left");
                        BigDecimal right = numberInput(context, "right");
                        context.output("out", left.add(right).stripTrailingZeros());
                    })
    ));

    private CanvasBuiltinLibrary() {
    }

    public static CanvasNodeRegistry registry() {
        return REGISTRY;
    }

    private static BigDecimal numberInput(CanvasNodeExecutionContext context, String portName) {
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

    private static Object requiredInput(CanvasNodeExecutionContext context, String portName) {
        if (!context.hasInput(portName)) {
            throw new IllegalArgumentException("node requires an input on port: " + portName);
        }
        return context.input(portName);
    }
}
