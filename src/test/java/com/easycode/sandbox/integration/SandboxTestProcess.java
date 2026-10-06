package com.easycode.sandbox.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Small real child process used by integration tests. */
public final class SandboxTestProcess {
    private SandboxTestProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("test process operation is required");
        }
        switch (args[0]) {
            case "stdout-stderr" -> {
                System.out.println("sandbox-stdout");
                System.err.println("sandbox-stderr");
            }
            case "large-output" -> System.out.print("x".repeat(Integer.parseInt(args[1])));
            case "sleep" -> Thread.sleep(Long.parseLong(args[1]));
            case "write" -> Files.writeString(Path.of(args[1]), args[2]);
            case "read" -> System.out.print(Files.readString(Path.of(args[1])));
            case "env" -> System.out.print(System.getenv(args[1]));
            case "cwd" -> System.out.print(Path.of("").toAbsolutePath().normalize());
            case "exit" -> System.exit(Integer.parseInt(args[1]));
            case "spawn-child" -> spawnChild(args);
            default -> throw new IllegalArgumentException("unknown test process operation: " + args[0]
                    + ", args=" + Arrays.toString(args));
        }
    }

    private static void spawnChild(String[] args) throws Exception {
        Path childPidFile = Path.of(args[1]);
        long childSleepMillis = Long.parseLong(args[2]);
        long parentSleepMillis = Long.parseLong(args[3]);
        Process child = new ProcessBuilder(
                javaExecutable(),
                "-cp", testClasspath(),
                SandboxTestProcess.class.getName(),
                "sleep", Long.toString(childSleepMillis))
                .redirectErrorStream(true)
                .start();
        Files.writeString(childPidFile, Long.toString(child.pid()));
        Thread.sleep(parentSleepMillis);
    }

    public static String javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    public static String testClasspath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    public static java.util.List<String> command(String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(testClasspath());
        command.add(SandboxTestProcess.class.getName());
        command.addAll(java.util.List.of(arguments));
        return command;
    }
}
