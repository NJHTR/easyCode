package com.easycode.execution.host;

import com.easycode.execution.api.ExecutionBackend;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;

/** Runs one request as a normal process on the local host. */
public final class HostExecutionBackend implements ExecutionBackend {
    private final ExecutorService outputReaders = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "easycode-execution-output");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = Instant.now();
        if (request.executionEnvironment() != ExecutionEnvironment.HOST) {
            return result(request, startedAt, ExecutionStatus.FAILED, null, "", "",
                    ExecutionTerminationReason.START_FAILED,
                    "HostExecutionBackend cannot execute "
                            + request.executionEnvironment() + " requests");
        }
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command())
                    .redirectErrorStream(false);
            if (request.workingDirectory() != null) {
                builder.directory(request.workingDirectory().toFile());
            }
            builder.environment().putAll(request.environmentVariables());
            process = builder.start();
        } catch (IOException | RuntimeException exception) {
            return result(request, startedAt, ExecutionStatus.FAILED, null, "", "",
                    ExecutionTerminationReason.START_FAILED, exception.getMessage());
        }

        Future<String> stdout = outputReaders.submit(
                () -> readOutput(process.getInputStream(), request.maxOutputChars()));
        Future<String> stderr = outputReaders.submit(
                () -> readOutput(process.getErrorStream(), request.maxOutputChars()));
        try {
            if (!process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                cancelReaders(stdout, stderr);
                return result(request, startedAt, ExecutionStatus.TIMED_OUT, null,
                        readQuietly(stdout), readQuietly(stderr),
                        ExecutionTerminationReason.TIMED_OUT,
                        "execution exceeded " + request.timeout().toMillis() + " ms");
            }

            String output = await(stdout);
            String error = await(stderr);
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return result(request, startedAt, ExecutionStatus.SUCCEEDED, exitCode,
                        output, error, ExecutionTerminationReason.COMPLETED, "");
            }
            return result(request, startedAt, ExecutionStatus.FAILED, exitCode,
                    output, error, ExecutionTerminationReason.NON_ZERO_EXIT,
                    "process exited with code " + exitCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            cancelReaders(stdout, stderr);
            return result(request, startedAt, ExecutionStatus.CANCELLED, null,
                    readQuietly(stdout), readQuietly(stderr),
                    ExecutionTerminationReason.CANCELLED, "execution thread was interrupted");
        } catch (ExecutionException exception) {
            destroyProcessTree(process);
            cancelReaders(stdout, stderr);
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            ExecutionTerminationReason reason = cause instanceof IOException
                    ? ExecutionTerminationReason.OUTPUT_LIMIT
                    : ExecutionTerminationReason.INTERNAL_ERROR;
            return result(request, startedAt, ExecutionStatus.FAILED, null,
                    readQuietly(stdout), readQuietly(stderr), reason, cause.getMessage());
        } finally {
            destroyProcessTree(process);
        }
    }

    @Override
    public void close() {
        outputReaders.shutdownNow();
    }

    private static String readOutput(InputStream input, int maxOutputChars) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (result.length() + read > maxOutputChars) {
                    throw new IOException("execution output exceeded " + maxOutputChars + " characters");
                }
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private static String await(Future<String> output)
            throws InterruptedException, ExecutionException {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new ExecutionException("execution output reader did not finish", exception);
        }
    }

    private static String readQuietly(Future<String> output) {
        try {
            return output.get(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (CancellationException | ExecutionException | TimeoutException exception) {
            return "";
        }
    }

    private static void cancelReaders(Future<String> stdout, Future<String> stderr) {
        stdout.cancel(true);
        stderr.cancel(true);
    }

    private static ExecutionResult result(
            ExecutionRequest request,
            Instant startedAt,
            ExecutionStatus status,
            Integer exitCode,
            String stdout,
            String stderr,
            ExecutionTerminationReason reason,
            String failureMessage) {
        return new ExecutionResult(request.executionId(), status, exitCode, stdout, stderr,
                Duration.between(startedAt, Instant.now()), reason, failureMessage);
    }

    private static void destroyProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int index = descendants.size() - 1; index >= 0; index--) {
            try {
                descendants.get(index).destroyForcibly();
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
        } catch (InterruptedException exception) {
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
}
