package com.easycode.sandbox.model;

import java.time.Instant;

/** A bounded point-in-time view of a sandbox instance. */
public record SandboxInfo(
        SandboxHandle handle,
        SandboxStatus status,
        long processId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        String output,
        String error) {

    public SandboxInfo {
        output = output == null ? "" : output;
        error = error == null ? "" : error;
    }
}
