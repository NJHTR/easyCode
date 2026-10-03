package com.easycode.sandbox.demo;

import com.easycode.runtime.jvm.SandboxRunner;
import com.easycode.runtime.jvm.SandboxTask;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public final class SandboxDemo {
    private SandboxDemo() {
    }

    public static void main(String[] args) throws Exception {
        SandboxRunner sandbox = new SandboxRunner(new SandboxRunner.Limits(Duration.ofMillis(800), 64, 256, 4 * 1024));

        System.out.println("sum      = " + sandbox.run(new SandboxTask("sum", "1.5,2.25,3")));
        System.out.println("uppercase= " + sandbox.run(new SandboxTask("uppercase", "canvas node")));

        try {
            sandbox.run(new SandboxTask("sleep", "3000"));
            throw new AssertionError("the timeout should have stopped the worker");
        } catch (TimeoutException expected) {
            System.out.println("timeout  = " + expected.getMessage());
        }
    }
}
