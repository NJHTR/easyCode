package com.easycode.sandbox.demo;

import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;
import com.easycode.sandbox.windows.WindowsJobObjectSandboxManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

/** Smoke test for the Windows Job Object backend. */
public final class WindowsJobObjectDemo {
    private WindowsJobObjectDemo() {
    }

    public static void main(String[] args) throws Exception {
        SandboxLimits limits = new SandboxLimits(
                Duration.ofSeconds(5),
                4 * 1024,
                128L * 1024 * 1024,
                4);

        try (SandboxService service = new SandboxService(new WindowsJobObjectSandboxManager())) {
            SandboxHandle completed = service.create(new SandboxSpec(
                    "job-echo",
                    List.of("cmd.exe", "/c", "echo job-object-ok"),
                    Path.of("target", "job-demo"),
                    Map.of(),
                    limits,
                    SandboxPolicy.defaults()));
            waitForTerminal(service, completed);
            System.out.println(service.query(completed));

            SandboxHandle timedOut = service.create(new SandboxSpec(
                    "job-timeout",
                    List.of("cmd.exe", "/c", "ping -n 10 127.0.0.1 > nul"),
                    null,
                    Map.of(),
                    new SandboxLimits(Duration.ofMillis(100), 4 * 1024, 128L * 1024 * 1024, 4),
                    SandboxPolicy.defaults()));
            waitForTerminal(service, timedOut);
            System.out.println(service.query(timedOut));
        }
    }

    private static void waitForTerminal(SandboxService service, SandboxHandle handle)
            throws SandboxException, InterruptedException {
        while (true) {
            SandboxStatus status = service.query(handle).status();
            if (status == SandboxStatus.SUCCEEDED || status == SandboxStatus.FAILED
                    || status == SandboxStatus.TIMED_OUT || status == SandboxStatus.DESTROYED) {
                return;
            }
            Thread.sleep(20);
        }
    }
}
