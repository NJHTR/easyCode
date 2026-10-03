package com.easycode.sandbox.model;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of one sandbox instance. The alias is display-only. */
public record SandboxHandle(UUID id, String alias) {
    public SandboxHandle {
        Objects.requireNonNull(id, "id");
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
    }
}
