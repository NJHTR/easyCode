package com.easycode.sandbox.integration;

import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;
import com.easycode.sandbox.windows.WindowsAppContainerSandboxManager;
import com.easycode.sandbox.windows.WindowsJobObjectSandboxManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsSandboxIntegrationTest {
    private static final SandboxLimits LIMITS = new SandboxLimits(
            Duration.ofSeconds(5), 16 * 1024, 128L * 1024 * 1024, 8);
    @Test
    void jobObjectCapturesOutputAndEnforcesTimeout() throws Exception {
        try (SandboxService service = new SandboxService(new WindowsJobObjectSandboxManager())) {
            SandboxInfo output = awaitTerminal(service, service.create(spec(
                    "job-output",
                    List.of("cmd.exe", "/c", "echo sandbox-stdout & echo sandbox-stderr 1>&2"),
                    null,
                    LIMITS, SandboxPolicy.defaults())));
            assertEquals(SandboxStatus.SUCCEEDED, output.status(), output.toString());
            assertTrue(output.output().contains("sandbox-stdout"), output.output());
            assertTrue(output.error().contains("sandbox-stderr"), output.error());

            SandboxLimits timeout = new SandboxLimits(Duration.ofMillis(150), 4096, 128L * 1024 * 1024, 2);
            SandboxInfo timedOut = awaitTerminal(service, service.create(spec(
                    "job-timeout", List.of("cmd.exe", "/c", "ping -n 10 127.0.0.1 > nul"), null,
                    timeout, SandboxPolicy.defaults())));
            assertEquals(SandboxStatus.TIMED_OUT, timedOut.status());
        }
    }

    @Test
    void appContainerAllowsWorkspaceWriteAndDeniesUnauthorizedReadAndWrite()
            throws Exception {
        Path tempDirectory = Path.of("target", "sandbox-appcontainer-" + UUID.randomUUID());
        Files.createDirectories(tempDirectory);
        Path workspace = tempDirectory;
        Path protectedFile = Path.of("target", "sandbox-protected-" + UUID.randomUUID() + ".txt")
                .toAbsolutePath();
        Files.writeString(protectedFile, "host-only");
        Path workspaceFile = workspace.resolve("workspace.txt");

        try (SandboxService service = new SandboxService(new WindowsAppContainerSandboxManager())) {
            SandboxInfo workspaceWrite = awaitTerminal(service, service.create(cmdSpec(
                    "workspace-write", "echo workspace-ok > workspace.txt", workspace,
                    SandboxPolicy.defaults())));
            assertEquals(SandboxStatus.SUCCEEDED, workspaceWrite.status(), workspaceWrite.toString());
            assertEquals("workspace-ok", Files.readString(workspaceFile).trim());

            SandboxInfo deniedRead = awaitTerminal(service, service.create(cmdSpec(
                    "denied-read", "type " + protectedFile, workspace,
                    SandboxPolicy.defaults())));
            assertEquals(SandboxStatus.FAILED, deniedRead.status());

            SandboxInfo deniedWrite = awaitTerminal(service, service.create(cmdSpec(
                    "denied-write", "echo changed > " + protectedFile, workspace,
                    SandboxPolicy.defaults())));
            assertEquals(SandboxStatus.FAILED, deniedWrite.status());
            assertEquals("host-only", Files.readString(protectedFile));

            SandboxInfo allowedRead = awaitTerminal(service, service.create(cmdSpec(
                    "allowed-read", "type " + protectedFile, workspace,
                    new SandboxPolicy(false, List.of(protectedFile), List.of()))));
            assertEquals(SandboxStatus.SUCCEEDED, allowedRead.status());
            assertTrue(allowedRead.output().contains("host-only"), allowedRead.output());
        } finally {
            deleteRecursively(tempDirectory);
            Files.deleteIfExists(protectedFile);
        }
    }

    private static SandboxSpec spec(
            String alias,
            List<String> command,
            Path workingDirectory,
            SandboxLimits limits,
            SandboxPolicy policy) {
        return new SandboxSpec(alias, command, workingDirectory, Map.of(), limits, policy);
    }

    private static SandboxSpec cmdSpec(
            String alias, String command, Path workingDirectory, SandboxPolicy policy) {
        return spec(alias, List.of("cmd.exe", "/c", command), workingDirectory, LIMITS, policy);
    }

    private static SandboxInfo awaitTerminal(SandboxService service, SandboxHandle handle)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            SandboxInfo info = service.query(handle);
            if (info.status() == SandboxStatus.SUCCEEDED
                    || info.status() == SandboxStatus.FAILED
                    || info.status() == SandboxStatus.TIMED_OUT
                    || info.status() == SandboxStatus.DESTROYED) {
                return info;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("sandbox did not reach a terminal state: " + handle);
    }

    private static void deleteRecursively(Path directory) throws Exception {
        if (Files.notExists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    path.toFile().deleteOnExit();
                }
            });
        }
    }
}
