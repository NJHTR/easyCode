package com.easycode.runtime.jvm;

/** A small, line-oriented task sent to a worker JVM. */
public record SandboxTask(String operation, String payload) {
    public SandboxTask {
        if (operation == null || operation.isBlank() || operation.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("operation must be a non-empty single line");
        }
        if (payload == null || payload.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("payload must be a single line");
        }
    }
}
