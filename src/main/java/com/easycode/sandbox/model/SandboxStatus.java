package com.easycode.sandbox.model;

/** Lifecycle state of a sandbox instance. */
public enum SandboxStatus {
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    OUTPUT_LIMIT,
    DESTROYED
}
