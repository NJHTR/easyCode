package com.easycode.execution.integration;

import com.easycode.execution.api.ExecutionService;
import com.easycode.execution.api.ExecutionBackend;
import com.easycode.execution.host.HostExecutionBackend;
import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;
import com.easycode.execution.runtime.jvm.JvmWorkerRuntime;
import com.easycode.execution.sandbox.SandboxExecutionBackend;
import com.easycode.execution.routing.EnvironmentExecutionBackend;
import com.easycode.runtime.jvm.SandboxTask;
import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.process.ProcessSandboxManager;
import com.easycode.sandbox.windows.WindowsJobObjectSandboxManager;
import com.easycode.sandbox.integration.SandboxTestProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFoundationIntegrationTest {
    @Test
    void hostExecutionCapturesOutput() {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(request(
                    SandboxTestProcess.command("stdout-stderr"), Duration.ofSeconds(5), Map.of()));

            assertEquals(ExecutionStatus.SUCCEEDED, result.status(), result.toString());
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("sandbox-stdout"), result.stdout());
            assertTrue(result.stderr().contains("sandbox-stderr"), result.stderr());
            assertEquals(ExecutionTerminationReason.COMPLETED, result.terminationReason());
        }
    }

    @Test
    void hostTimeoutIsMappedToExecutionResult() {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(request(
                    SandboxTestProcess.command("sleep", "5000"), Duration.ofMillis(150), Map.of()));

            assertEquals(ExecutionStatus.TIMED_OUT, result.status(), result.toString());
            assertEquals(ExecutionTerminationReason.TIMED_OUT, result.terminationReason());
        }
    }

    @Test
    void sandboxOutputLimitIsMappedToExecutionFailure() {
        try (SandboxService sandbox = new SandboxService(new ProcessSandboxManager());
             ExecutionService service = new ExecutionService(new SandboxExecutionBackend(sandbox))) {
            ExecutionRequest request = new ExecutionRequest(
                    UUID.randomUUID(),
                    SandboxTestProcess.command("large-output", "128"),
                    null,
                    Map.of(),
                    Duration.ofSeconds(5),
                    ExecutionEnvironment.SANDBOX,
                    16,
                    SandboxPolicy.defaults());

            ExecutionResult result = service.execute(request);

            assertEquals(ExecutionStatus.FAILED, result.status(), result.toString());
            assertEquals(ExecutionTerminationReason.OUTPUT_LIMIT, result.terminationReason());
            assertTrue(result.failureMessage().contains("output exceeded"), result.toString());
        }
    }

    @Test
    void hostTimeoutTerminatesChildProcesses(@TempDir Path workingDirectory) throws Exception {
        Path childPidFile = workingDirectory.resolve("child.pid");
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(new ExecutionRequest(
                    java.util.UUID.randomUUID(),
                    SandboxTestProcess.command("spawn-child", childPidFile.toString(),
                            "10000", "10000"),
                    workingDirectory,
                    Map.of(),
                    Duration.ofSeconds(2),
                    ExecutionEnvironment.HOST,
                    4096,
                    SandboxPolicy.defaults()));

            assertEquals(ExecutionStatus.TIMED_OUT, result.status(), result.toString());
            long childPid = Long.parseLong(Files.readString(childPidFile));
            waitForProcessExit(childPid);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void hostCompletionTerminatesChildProcesses(@TempDir Path workingDirectory) throws Exception {
        Path childPidFile = workingDirectory.resolve("child-after-parent.pid");
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(new ExecutionRequest(
                    java.util.UUID.randomUUID(),
                    SandboxTestProcess.command("spawn-child", childPidFile.toString(),
                            "10000", "0"),
                    workingDirectory,
                    Map.of(),
                    Duration.ofSeconds(5),
                    ExecutionEnvironment.HOST,
                    4096,
                    SandboxPolicy.defaults()));

            assertEquals(ExecutionStatus.SUCCEEDED, result.status(), result.toString());
            long childPid = Long.parseLong(Files.readString(childPidFile));
            waitForProcessExit(childPid);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void hostFailureIncludesStartReason() {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(request(
                    List.of("easycode-command-that-does-not-exist"), Duration.ofSeconds(5), Map.of()));

            assertEquals(ExecutionStatus.FAILED, result.status(), result.toString());
            assertEquals(ExecutionTerminationReason.START_FAILED, result.terminationReason());
            assertTrue(!result.failureMessage().isBlank(), result.toString());
        }
    }

    @Test
    void hostBackendRejectsSandboxRequest() {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(new ExecutionRequest(
                    UUID.randomUUID(), List.of("ignored"), null, Map.of(),
                    Duration.ofSeconds(1), ExecutionEnvironment.SANDBOX, 4096,
                    SandboxPolicy.defaults()));

            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(ExecutionTerminationReason.START_FAILED, result.terminationReason());
            assertTrue(result.failureMessage().contains("cannot execute SANDBOX"));
        }
    }

    @Test
    void sandboxBackendRejectsHostRequest() {
        try (SandboxService sandbox = new SandboxService(new ProcessSandboxManager());
             ExecutionService service = new ExecutionService(new SandboxExecutionBackend(sandbox))) {
            ExecutionResult result = service.execute(new ExecutionRequest(
                    UUID.randomUUID(), List.of("ignored"), null, Map.of(),
                    Duration.ofSeconds(1), ExecutionEnvironment.HOST, 4096,
                    SandboxPolicy.defaults()));

            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(ExecutionTerminationReason.START_FAILED, result.terminationReason());
            assertTrue(result.failureMessage().contains("cannot execute HOST"));
        }
    }

    @Test
    void hostPassesEnvironmentAndWorkingDirectory(@TempDir Path workingDirectory) {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionRequest environmentRequest = request(
                    SandboxTestProcess.command("env", "EASYCODE_TEST"),
                    Duration.ofSeconds(5), Map.of("EASYCODE_TEST", "value"));
            ExecutionRequest workingDirectoryRequest = new ExecutionRequest(
                    java.util.UUID.randomUUID(),
                    SandboxTestProcess.command("cwd"),
                    workingDirectory,
                    Map.of(),
                    Duration.ofSeconds(5),
                    ExecutionEnvironment.HOST,
                    4096,
                    SandboxPolicy.defaults());

            assertEquals("value", service.execute(environmentRequest).stdout());
            assertEquals(workingDirectory.toAbsolutePath().normalize().toString(),
                    service.execute(workingDirectoryRequest).stdout());
        }
    }

    @Test
    void explicitProviderEnvironmentRemainsAvailableToTheChild() {
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionRequest request = request(
                    SandboxTestProcess.command("env", "EASYCODE_LLM_API_KEY"),
                    Duration.ofSeconds(5), Map.of("EASYCODE_LLM_API_KEY", "explicit-value"));

            assertEquals("explicit-value", service.execute(request).stdout());
        }
    }

    @Test
    void jvmWorkerRuntimeBuildsExecutableRequest() {
        JvmWorkerRuntime runtime = new JvmWorkerRuntime();
        ExecutionRequest request = runtime.request(
                new SandboxTask("uppercase", "execution"),
                ExecutionEnvironment.HOST,
                Duration.ofSeconds(5));

        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(request);
            assertEquals(ExecutionStatus.SUCCEEDED, result.status(), result.toString());
            assertEquals("EXECUTION", result.stdout());
        }
    }

    @Test
    void routesRequestsToTheBackendSelectedByEnvironment() {
        RecordingBackend host = new RecordingBackend("host");
        RecordingBackend sandbox = new RecordingBackend("sandbox");
        try (ExecutionService service = new ExecutionService(
                new EnvironmentExecutionBackend(host, sandbox))) {
            ExecutionResult hostResult = service.execute(request(
                    SandboxTestProcess.command("stdout-stderr"), Duration.ofSeconds(5), Map.of()));
            ExecutionResult sandboxResult = service.execute(new ExecutionRequest(
                    UUID.randomUUID(),
                    List.of("ignored"),
                    null,
                    Map.of(),
                    Duration.ofSeconds(5),
                    ExecutionEnvironment.SANDBOX,
                    4096,
                    SandboxPolicy.defaults()));

            assertEquals("host", hostResult.stdout());
            assertEquals("sandbox", sandboxResult.stdout());
            assertEquals(1, host.calls);
            assertEquals(1, sandbox.calls);
        }
    }

    @Test
    void reportsMissingEnvironmentBackendWithoutFallback() {
        RecordingBackend host = new RecordingBackend("host");
        try (ExecutionService service = new ExecutionService(
                new EnvironmentExecutionBackend(Map.of(ExecutionEnvironment.HOST, host)))) {
            ExecutionResult result = service.execute(new ExecutionRequest(
                    UUID.randomUUID(), List.of("ignored"), null, Map.of(),
                    Duration.ofSeconds(1), ExecutionEnvironment.SANDBOX, 4096,
                    SandboxPolicy.defaults()));

            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(ExecutionTerminationReason.START_FAILED, result.terminationReason());
            assertTrue(result.failureMessage().contains("no execution backend configured"));
            assertEquals(0, host.calls);
        }
    }

    @Test
    void normalizesBackendResultWithMismatchedExecutionId() {
        ExecutionRequest request = request(
                SandboxTestProcess.command("stdout-stderr"), Duration.ofSeconds(5), Map.of());
        ExecutionBackend backend = new ExecutionBackend() {
            @Override
            public ExecutionResult execute(ExecutionRequest ignored) {
                return new ExecutionResult(
                        UUID.randomUUID(), ExecutionStatus.SUCCEEDED, 0, "wrong", "",
                        Duration.ZERO, ExecutionTerminationReason.COMPLETED, "");
            }

            @Override
            public void close() {
            }
        };

        try (ExecutionService service = new ExecutionService(backend)) {
            ExecutionResult result = service.execute(request);

            assertEquals(request.executionId(), result.executionId());
            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(ExecutionTerminationReason.INTERNAL_ERROR,
                    result.terminationReason());
            assertEquals("execution backend returned a mismatched execution id",
                    result.failureMessage());
        }
    }

    @Test
    void normalizesBackendReturningNoResult() {
        try (ExecutionService service = new ExecutionService(new ExecutionBackend() {
            @Override
            public ExecutionResult execute(ExecutionRequest request) {
                return null;
            }

            @Override
            public void close() {
            }
        })) {
            ExecutionRequest request = request(
                    SandboxTestProcess.command("stdout-stderr"), Duration.ofSeconds(5), Map.of());
            ExecutionResult result = service.execute(request);

            assertEquals(request.executionId(), result.executionId());
            assertEquals(ExecutionStatus.FAILED, result.status());
            assertEquals(ExecutionTerminationReason.INTERNAL_ERROR,
                    result.terminationReason());
            assertEquals("execution backend returned no result", result.failureMessage());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void sandboxExecutionUsesExistingSandboxService() throws Exception {
        try (SandboxService sandboxService = new SandboxService(new WindowsJobObjectSandboxManager());
             ExecutionService service = new ExecutionService(new SandboxExecutionBackend(sandboxService))) {
            ExecutionRequest request = new ExecutionRequest(
                    java.util.UUID.randomUUID(),
                    List.of("cmd.exe", "/c", "echo sandbox-ok"),
                    null,
                    Map.of(),
                    Duration.ofSeconds(5),
                    ExecutionEnvironment.SANDBOX,
                    4096,
                    SandboxPolicy.defaults());
            ExecutionResult result = service.execute(request);

            assertEquals(ExecutionStatus.SUCCEEDED, result.status(), result.toString());
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("sandbox-ok"), result.stdout());
        }
    }

    private static ExecutionRequest request(
            List<String> command, Duration timeout, Map<String, String> environment) {
        return new ExecutionRequest(
                java.util.UUID.randomUUID(), command, null, environment, timeout,
                ExecutionEnvironment.HOST, 4096, SandboxPolicy.defaults());
    }

    private static void waitForProcessExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static final class RecordingBackend implements com.easycode.execution.api.ExecutionBackend {
        private final String output;
        private int calls;

        private RecordingBackend(String output) {
            this.output = output;
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request) {
            calls++;
            return new ExecutionResult(
                    request.executionId(), ExecutionStatus.SUCCEEDED, 0, output, "",
                    Duration.ZERO, ExecutionTerminationReason.COMPLETED, "");
        }

        @Override
        public void close() {
        }
    }
}
