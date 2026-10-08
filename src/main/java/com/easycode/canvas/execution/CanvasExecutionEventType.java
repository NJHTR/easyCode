package com.easycode.canvas.execution;

/** Synchronous lifecycle points that can be observed during one canvas run. */
public enum CanvasExecutionEventType {
    STARTED,
    NODE_STARTED,
    NODE_SUCCEEDED,
    NODE_FAILED,
    SUCCEEDED,
    FAILED
}
