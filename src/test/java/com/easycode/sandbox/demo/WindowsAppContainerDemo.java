package com.easycode.sandbox.demo;

import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;
import com.easycode.sandbox.windows.WindowsAppContainerSandboxManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;

/** Smoke test for the AppContainer plus Job Object backend. */
public final class WindowsAppContainerDemo {
    private WindowsAppContainerDemo() {
    }

    public static void main(String[] args) throws Exception {
        SandboxLimits limits = new SandboxLimits(
                Duration.ofSeconds(5),
                4 * 1024,
                128L * 1024 * 1024,
                4);

        try (SandboxService service = new SandboxService(new WindowsAppContainerSandboxManager())) {
            SandboxHandle handle = service.create(new SandboxSpec(
                    "app-container-echo",
                    List.of("cmd.exe", "/c", "echo app-container-ok"),
                    Path.of("target", "app-container-demo"),
                    Map.of(),
                    limits,
                    SandboxPolicy.defaults()));
            waitForTerminal(service, handle);
            System.out.println(service.query(handle));

            Path protectedFile = Path.of("target", "app-container-protected.txt").toAbsolutePath();
            Files.writeString(protectedFile, "host-only");
            try {
                SandboxHandle denied = service.create(new SandboxSpec(
                        "app-container-denied",
                        List.of("cmd.exe", "/c", "type", protectedFile.toString()),
                        Path.of("target", "app-container-demo"),
                        Map.of(),
                        limits,
                        SandboxPolicy.defaults()));
                waitForTerminal(service, denied);
                System.out.println(service.query(denied));

                SandboxHandle allowed = service.create(new SandboxSpec(
                        "app-container-allowed",
                        List.of("cmd.exe", "/c", "type", protectedFile.toString()),
                        Path.of("target", "app-container-demo"),
                        Map.of(),
                        limits,
                        new SandboxPolicy(false, List.of(protectedFile), List.of())));
                waitForTerminal(service, allowed);
                System.out.println(service.query(allowed));
            } finally {
                Files.deleteIfExists(protectedFile);
            }
        }
    }

    private static void waitForTerminal(SandboxService service, SandboxHandle handle)
            throws SandboxException, InterruptedException {
        while (true) {
            SandboxStatus status = service.query(handle).status();
            if (status == SandboxStatus.SUCCEEDED || status == SandboxStatus.FAILED
                    || status == SandboxStatus.TIMED_OUT
                    || status == SandboxStatus.OUTPUT_LIMIT
                    || status == SandboxStatus.DESTROYED) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
