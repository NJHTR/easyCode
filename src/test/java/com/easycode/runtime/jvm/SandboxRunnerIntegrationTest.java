package com.easycode.runtime.jvm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxRunnerIntegrationTest {
    @Test
    void runsTheLegacyJvmWorker() throws Exception {
        SandboxRunner runner = new SandboxRunner(new SandboxRunner.Limits(
                Duration.ofSeconds(5), 64, 256, 4096));

        assertEquals("CANVAS NODE", runner.run(new SandboxTask("uppercase", "canvas node")));
        assertEquals("6.75", runner.run(new SandboxTask("sum", "1.5,2.25,3")));
    }

    @Test
    void reportsWorkerTimeout() {
        SandboxRunner runner = new SandboxRunner(new SandboxRunner.Limits(
                Duration.ofMillis(150), 64, 256, 4096));

        assertThrows(TimeoutException.class,
                () -> runner.run(new SandboxTask("sleep", "5000")));
    }

    @Test
    void reportsWorkerFailure() {
        SandboxRunner runner = new SandboxRunner(new SandboxRunner.Limits(
                Duration.ofSeconds(5), 64, 256, 4096));

        assertThrows(IOException.class,
                () -> runner.run(new SandboxTask("unsupported", "input")));
    }
}
