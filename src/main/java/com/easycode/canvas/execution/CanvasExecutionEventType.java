package com.easycode.canvas.execution;

/** Synchronous lifecycle points that can be observed during one canvas run. */
public enum CanvasExecutionEventType {
    STARTED,
    DEBUGGER_PAUSED,
    DEBUGGER_RESUMED,
    NODE_STARTED,
    NODE_CONSOLE_OUTPUT,
    NODE_SUCCEEDED,
    NODE_FAILED,
    NODE_CANCELLED,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
