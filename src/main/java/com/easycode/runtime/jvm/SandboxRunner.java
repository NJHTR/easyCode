package com.easycode.runtime.jvm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Starts one short-lived worker JVM per task.
 *
 * This provides process and JVM-level resource boundaries. It is not an OS security
 * sandbox by itself; production code must add AppContainer, bubblewrap,
 * App Sandbox, or another OS-level mechanism for hostile Java bytecode.
 */
public final class SandboxRunner {
    private final Limits limits;

    public SandboxRunner(Limits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public String run(SandboxTask task) throws IOException, InterruptedException, TimeoutException {
        Objects.requireNonNull(task, "task");
        Path workDirectory = Files.createTempDirectory("easycode-sandbox-");
        Process process = null;
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sandbox-output-reader");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.add("-Xmx" + limits.maxHeapMb() + "m");
            command.add("-Xss" + limits.stackKb() + "k");
            command.add("-XX:ActiveProcessorCount=1");
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Duser.dir=" + workDirectory);
            command.add("-Djava.io.tmpdir=" + workDirectory);
            command.add("-Duser.home=" + workDirectory);
            command.add("-cp");
            command.add(absoluteClassPath());
            command.add(SandboxWorker.class.getName());

            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workDirectory.toFile())
                    .redirectErrorStream(true);
            Process startedProcess = builder.start();
            process = startedProcess;

            Future<String> output = readerExecutor.submit(
                    () -> readOutput(startedProcess.getInputStream(), limits.maxOutputChars()));
            sendTask(startedProcess, task);

            if (!process.waitFor(limits.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                output.cancel(true);
                throw new TimeoutException("sandbox task exceeded " + limits.timeout().toMillis() + " ms");
            }

            String result;
            try {
                result = output.get(1, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                throw new IOException("failed to read sandbox output", exception.getCause());
            } catch (java.util.concurrent.TimeoutException exception) {
                throw new IOException("sandbox output reader did not finish", exception);
            }

            if (process.exitValue() != 0) {
                throw new IOException("sandbox worker failed with exit code "
                        + process.exitValue() + ": " + result);
            }
            return result;
        } finally {
            if (process != null) {
                destroyProcessTree(process);
            }
            readerExecutor.shutdownNow();
            deleteDirectory(workDirectory);
        }
    }

    private static void sendTask(Process process, SandboxTask task) throws IOException {
        try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(task.operation());
            writer.write('\n');
            writer.write(task.payload());
            writer.write('\n');
        }
    }

    private static String readOutput(InputStream input, int maxOutputChars) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[512];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (result.length() + read > maxOutputChars) {
                    throw new IOException("sandbox output exceeded " + maxOutputChars + " characters");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String absoluteClassPath() {
        String separator = System.getProperty("path.separator");
        String[] entries = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(separator));
        return java.util.Arrays.stream(entries)
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .reduce((left, right) -> left + separator + right)
                .orElseThrow(() -> new IllegalStateException("java.class.path is empty"));
    }

    private static void destroyProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            ProcessHandle child = descendants.get(index);
            try {
                child.destroyForcibly();
            } catch (SecurityException ignored) {
                // Best effort cleanup; the parent is forcibly terminated below.
            }
        }
        for (int index = descendants.size() - 1; index >= 0; index--) {
            awaitExit(descendants.get(index));
        }
        process.destroyForcibly();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitExit(ProcessHandle process) {
        try {
            process.onExit().get(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // Cleanup remains best effort when a child does not exit promptly.
        }
    }

    private static void deleteDirectory(Path directory) {
        try {
            if (Files.notExists(directory)) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException exception) {
                                path.toFile().deleteOnExit();
                            }
                        });
            }
        } catch (IOException ignored) {
            // Defer deletion to the JVM; cleanup must not alter the result.
            directory.toFile().deleteOnExit();
        }
    }

    public record Limits(Duration timeout, int maxHeapMb, int stackKb, int maxOutputChars) {
        public Limits {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxHeapMb < 16 || maxHeapMb > 4096) {
                throw new IllegalArgumentException("maxHeapMb must be between 16 and 4096");
            }
            if (stackKb < 128 || stackKb > 1024) {
                throw new IllegalArgumentException("stackKb must be between 128 and 1024");
            }
            if (maxOutputChars < 1 || maxOutputChars > 10_000_000) {
                throw new IllegalArgumentException("maxOutputChars is out of range");
            }
        }

        public static Limits defaults() {
            return new Limits(Duration.ofSeconds(2), 64, 256, 64 * 1024);
        }
    }
}
