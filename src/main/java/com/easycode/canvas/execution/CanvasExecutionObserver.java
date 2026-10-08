package com.easycode.canvas.execution;

/** Receives synchronous lifecycle events without owning execution or storage. */
@FunctionalInterface
public interface CanvasExecutionObserver {
    void onEvent(CanvasExecutionEvent event);

    static CanvasExecutionObserver noop() {
        return event -> { };
    }
}
