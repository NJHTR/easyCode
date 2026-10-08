package com.easycode.canvas.execution;

/** Result of querying an execution snapshot while preserving an explicit null value. */
public record CanvasValueLookup(boolean present, Object value) {
    private static final CanvasValueLookup MISSING = new CanvasValueLookup(false, null);

    public CanvasValueLookup {
        if (!present && value != null) {
            throw new IllegalArgumentException("missing canvas value cannot carry a value");
        }
    }

    public static CanvasValueLookup present(Object value) {
        return new CanvasValueLookup(true, value);
    }

    public static CanvasValueLookup missing() {
        return MISSING;
    }

    public Object requireValue() {
        if (!present) {
            throw new IllegalStateException("canvas value is not present");
        }
        return value;
    }
}
