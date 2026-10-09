package com.easycode.canvas.execution;

/** Reason why a debuggable canvas execution is paused at a node boundary. */
public enum CanvasExecutionPauseReason {
    BREAKPOINT,
    REQUESTED,
    STEP
}
