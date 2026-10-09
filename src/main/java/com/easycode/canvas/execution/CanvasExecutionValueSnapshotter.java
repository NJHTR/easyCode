package com.easycode.canvas.execution;

/**
 * Converts custom runtime values into independent immutable representations
 * for execution trace or event inspection.
 */
@FunctionalInterface
public interface CanvasExecutionValueSnapshotter {
    /**
     * Returns an immutable snapshot of a custom value. Returning the same
     * instance keeps the default reference behavior for that value.
     */
    Object snapshot(Object value);

    static CanvasExecutionValueSnapshotter identity() {
        return value -> value;
    }
}
