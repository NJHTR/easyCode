package com.easycode.sandbox.demo;

import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxPolicy;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;
import com.easycode.sandbox.process.ProcessSandboxManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Demonstrates the sandbox lifecycle with ordinary Windows processes. */
public final class SandboxLifecycleDemo {
    private SandboxLifecycleDemo() {
    }

    public static void main(String[] args) throws Exception {
        SandboxLimits limits = new SandboxLimits(
                Duration.ofSeconds(10),
                4 * 1024,
                256L * 1024 * 1024,
                4);

        try (SandboxService service = new SandboxService(new ProcessSandboxManager())) {
            SandboxHandle completed = service.create(new SandboxSpec(
                    "echo-demo",
                    List.of("cmd.exe", "/c", "echo sandbox-ok"),
                    null,
                    Map.of(),
                    limits,
                    SandboxPolicy.defaults()));
            waitForTerminal(service, completed);
            System.out.println("completed = " + service.query(completed));

            SandboxHandle timedOut = service.create(new SandboxSpec(
                    "timeout-demo",
                    List.of("cmd.exe", "/c", "ping -n 10 127.0.0.1 > nul"),
                    null,
                    Map.of(),
                    new SandboxLimits(Duration.ofMillis(100), 4 * 1024, 0, 4),
                    SandboxPolicy.defaults()));
            waitForTerminal(service, timedOut);
            System.out.println("timeout   = " + service.query(timedOut));

            SandboxHandle running = service.create(new SandboxSpec(
                    "long-running-demo",
                    List.of("cmd.exe", "/c", "ping -n 10 127.0.0.1 > nul"),
                    null,
                    Map.of(),
                    limits,
                    SandboxPolicy.defaults()));
            Thread.sleep(200);
            System.out.println("running   = " + service.query(running));
            service.destroy(running);
            System.out.println("destroyed = " + service.query(running));
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
