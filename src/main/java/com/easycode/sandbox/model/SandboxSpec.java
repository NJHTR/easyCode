package com.easycode.sandbox.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Backend-neutral description of a process that should run in a sandbox. */
public record SandboxSpec(
        String alias,
        List<String> command,
        Path workingDirectory,
        Map<String, String> environment,
        SandboxLimits limits,
        SandboxPolicy policy) {

    public SandboxSpec {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("alias must not be blank");
        }
        command = command == null ? List.of() : List.copyOf(command);
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain an executable");
        }
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        limits = Objects.requireNonNullElse(limits, SandboxLimits.defaults());
        policy = Objects.requireNonNullElse(policy, SandboxPolicy.defaults());
    }
}
