package com.easycode.sandbox.model;

import java.time.Duration;

/** Resource limits understood by a sandbox backend. */
public record SandboxLimits(
        Duration timeout,
        long maxOutputChars,
        long maxMemoryBytes,
        int maxProcessCount) {

    public SandboxLimits {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputChars < 1) {
            throw new IllegalArgumentException("maxOutputChars must be positive");
        }
        if (maxMemoryBytes < 0) {
            throw new IllegalArgumentException("maxMemoryBytes cannot be negative");
        }
        if (maxProcessCount < 1) {
            throw new IllegalArgumentException("maxProcessCount must be positive");
        }
    }

    public static SandboxLimits defaults() {
        return new SandboxLimits(Duration.ofSeconds(30), 1024 * 1024, 256L * 1024 * 1024, 16);
    }
}
