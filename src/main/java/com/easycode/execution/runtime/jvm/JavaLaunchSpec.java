package com.easycode.execution.runtime.jvm;

import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.sandbox.model.SandboxPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Describes one real java invocation without depending on an IDE. */
public record JavaLaunchSpec(
        Path javaHome,
        String mainClass,
        List<Path> classpath,
        List<String> programArguments,
        Path workingDirectory,
        Map<String, String> environmentVariables,
        Duration timeout,
        ExecutionEnvironment executionEnvironment,
        int maxOutputChars,
        SandboxPolicy sandboxPolicy) {

    public JavaLaunchSpec {
        if (mainClass == null || mainClass.isBlank()
                || mainClass.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("mainClass must be a non-blank class name");
        }
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (classpath.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("classpath cannot contain null");
        }
        programArguments = programArguments == null ? List.of() : List.copyOf(programArguments);
        if (programArguments.stream().anyMatch(argument -> argument == null)) {
            throw new IllegalArgumentException("programArguments cannot contain null");
        }
        environmentVariables = environmentVariables == null
                ? Map.of() : Map.copyOf(environmentVariables);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (executionEnvironment == null) {
            throw new IllegalArgumentException("executionEnvironment must not be null");
        }
        if (maxOutputChars < 1 || maxOutputChars > 10_000_000) {
            throw new IllegalArgumentException("maxOutputChars is out of range");
        }
        sandboxPolicy = sandboxPolicy == null ? SandboxPolicy.defaults() : sandboxPolicy;
    }
}
