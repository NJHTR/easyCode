package com.easycode.execution.model;

import com.easycode.sandbox.model.SandboxPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Backend-neutral description of one synchronous process execution. */
public record ExecutionRequest(
        UUID executionId,
        List<String> command,
        Path workingDirectory,
        Map<String, String> environmentVariables,
        Duration timeout,
        ExecutionEnvironment executionEnvironment,
        int maxOutputChars,
        SandboxPolicy sandboxPolicy) {

    public ExecutionRequest {
        Objects.requireNonNull(executionId, "executionId");
        command = command == null ? List.of() : List.copyOf(command);
        if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("command must contain an executable");
        }
        environmentVariables = environmentVariables == null
                ? Map.of() : Map.copyOf(environmentVariables);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Objects.requireNonNull(executionEnvironment, "executionEnvironment");
        if (maxOutputChars < 1 || maxOutputChars > 10_000_000) {
            throw new IllegalArgumentException("maxOutputChars is out of range");
        }
        sandboxPolicy = sandboxPolicy == null ? SandboxPolicy.defaults() : sandboxPolicy;
    }

    public static ExecutionRequest create(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environmentVariables,
            Duration timeout,
            ExecutionEnvironment executionEnvironment) {
        return new ExecutionRequest(
                UUID.randomUUID(), command, workingDirectory, environmentVariables,
                timeout, executionEnvironment, 1024 * 1024, SandboxPolicy.defaults());
    }
}
