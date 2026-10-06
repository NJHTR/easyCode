package com.easycode.sandbox.integration;

import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;
import com.easycode.sandbox.process.ProcessSandboxManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxLifecycleIntegrationTest {
    private static final SandboxLimits DEFAULT_LIMITS = new SandboxLimits(
            Duration.ofSeconds(5), 16 * 1024, 0, 8);

    @Test
    void capturesStdoutAndStderrFromRealProcess() throws Exception {
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec(
                    "output", SandboxTestProcess.command("stdout-stderr"), null, DEFAULT_LIMITS)));

            assertEquals(SandboxStatus.SUCCEEDED, info.status());
            assertTrue(info.output().contains("sandbox-stdout"), info.output());
            assertTrue(info.error().contains("sandbox-stderr"), info.error());
        }
    }

    @Test
    void enforcesTimeout() throws Exception {
        SandboxLimits limits = new SandboxLimits(Duration.ofMillis(150), 4096, 0, 2);
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec(
                    "timeout", SandboxTestProcess.command("sleep", "5000"), null, limits)));

            assertEquals(SandboxStatus.TIMED_OUT, info.status());
        }
    }

    @Test
    void reportsOutputLimit() throws Exception {
        SandboxLimits limits = new SandboxLimits(Duration.ofSeconds(5), 16, 0, 2);
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec(
                    "output-limit", SandboxTestProcess.command("large-output", "128"),
                    null, limits)));

            assertEquals(SandboxStatus.OUTPUT_LIMIT, info.status());
            assertTrue(!info.error().isBlank(), info.error());
        }
    }

    @Test
    void destroysParentAndChildProcesses(@TempDir Path tempDirectory) throws Exception {
        Path childPidFile = tempDirectory.resolve("child.pid");
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxHandle handle = service.create(spec("tree", SandboxTestProcess.command(
                    "spawn-child", childPidFile.toString(), "10000", "10000"), tempDirectory, DEFAULT_LIMITS));
            waitForFile(childPidFile);
            long childPid = Long.parseLong(Files.readString(childPidFile));

            service.destroy(handle);
            assertEquals(SandboxStatus.DESTROYED, service.query(handle).status());
            waitForProcessExit(childPid);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void normalCompletionDestroysChildProcesses(@TempDir Path tempDirectory) throws Exception {
        Path childPidFile = tempDirectory.resolve("child-after-parent.pid");
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec("tree-complete",
                    SandboxTestProcess.command("spawn-child", childPidFile.toString(),
                            "10000", "0"), tempDirectory, DEFAULT_LIMITS)));

            assertEquals(SandboxStatus.SUCCEEDED, info.status());
            long childPid = Long.parseLong(Files.readString(childPidFile));
            waitForProcessExit(childPid);
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void preservesExplicitWorkspaceAndAllowsWriting(@TempDir Path workspace) throws Exception {
        Path output = workspace.resolve("output.txt");
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec(
                    "workspace", SandboxTestProcess.command("write", output.toString(), "workspace-ok"),
                    workspace, DEFAULT_LIMITS)));

            assertEquals(SandboxStatus.SUCCEEDED, info.status());
            assertEquals("workspace-ok", Files.readString(output));
        }
    }

    @Test
    void reportsAbnormalExit() throws Exception {
        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxInfo info = awaitTerminal(service, service.create(spec(
                    "exit", SandboxTestProcess.command("exit", "7"), null, DEFAULT_LIMITS)));

            assertEquals(SandboxStatus.FAILED, info.status());
            assertEquals(7, info.exitCode());
        }
    }

    private static SandboxSpec spec(
            String alias, List<String> command, Path workingDirectory, SandboxLimits limits) {
        return new SandboxSpec(alias, command, workingDirectory, Map.of(), limits,
                SandboxPolicy.defaults());
    }

    private static SandboxInfo awaitTerminal(SandboxService service, SandboxHandle handle)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            SandboxInfo info = service.query(handle);
            if (info.status() == SandboxStatus.SUCCEEDED
                    || info.status() == SandboxStatus.FAILED
                    || info.status() == SandboxStatus.TIMED_OUT
                    || info.status() == SandboxStatus.OUTPUT_LIMIT
                    || info.status() == SandboxStatus.DESTROYED) {
                return info;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("sandbox did not reach a terminal state: " + handle);
    }

    private static void waitForFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while ((!Files.exists(file) || Files.size(file) == 0)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(file) && Files.size(file) > 0,
                "child pid file was not populated");
    }

    private static void waitForProcessExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
