package com.easycode.execution.runtime.jvm;

import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.sandbox.model.SandboxPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Describes one real javac invocation without depending on an IDE. */
public record JavaCompilationSpec(
        Path javaHome,
        List<Path> sourceFiles,
        Path outputDirectory,
        List<Path> classpath,
        Path workingDirectory,
        Map<String, String> environmentVariables,
        Duration timeout,
        ExecutionEnvironment executionEnvironment,
        int maxOutputChars,
        SandboxPolicy sandboxPolicy) {

    public JavaCompilationSpec {
        sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        if (sourceFiles.isEmpty() || sourceFiles.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("sourceFiles must contain at least one file");
        }
        if (outputDirectory == null) {
            throw new IllegalArgumentException("outputDirectory must not be null");
        }
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (classpath.stream().anyMatch(path -> path == null)) {
            throw new IllegalArgumentException("classpath cannot contain null");
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
