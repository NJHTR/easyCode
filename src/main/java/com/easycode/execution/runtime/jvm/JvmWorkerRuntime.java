package com.easycode.execution.runtime.jvm;

import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.runtime.jvm.SandboxTask;
import com.easycode.runtime.jvm.SandboxWorker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Builds an ExecutionRequest for the existing short-lived JVM worker. */
public final class JvmWorkerRuntime {
    public ExecutionRequest request(
            String operation,
            String payload,
            ExecutionEnvironment environment,
            Duration timeout) {
        return request(new SandboxTask(operation, payload), environment, timeout);
    }

    public ExecutionRequest request(
            SandboxTask task, ExecutionEnvironment environment, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(absoluteClassPath());
        command.add(SandboxWorker.class.getName());
        command.add(task.operation());
        command.add(task.payload());
        return new ExecutionRequest(
                java.util.UUID.randomUUID(), command, null, Map.of(), timeout,
                environment, 64 * 1024, com.easycode.sandbox.model.SandboxPolicy.defaults());
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String absoluteClassPath() {
        String separator = System.getProperty("path.separator");
        String[] entries = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator));
        return Arrays.stream(entries)
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .reduce((left, right) -> left + separator + right)
                .orElseThrow(() -> new IllegalStateException("java.class.path is empty"));
    }
}
