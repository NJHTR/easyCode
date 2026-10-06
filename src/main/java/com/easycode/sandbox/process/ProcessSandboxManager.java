package com.easycode.sandbox.process;

import com.easycode.sandbox.api.SandboxBackend;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
//import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxSpec;
import com.easycode.sandbox.model.SandboxStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * First backend for validating the sandbox lifecycle.
 * <p>
 * It provides process isolation and bounded cleanup only. It does not enforce
 * Windows file/network permissions; those belong to the native backend.
 */
public final class ProcessSandboxManager implements SandboxBackend {
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "easycode-sandbox");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public SandboxHandle create(SandboxSpec spec) throws SandboxException {
        Objects.requireNonNull(spec, "spec");
        UUID id = UUID.randomUUID();
        SandboxHandle handle = new SandboxHandle(id, spec.alias());
        Session session = new Session(handle, spec);
        sessions.put(id, session);
        try {
            session.start();
            return handle;
        } catch (SandboxException exception) {
            sessions.remove(id);
            throw exception;
        }
    }

    @Override
    public SandboxInfo query(SandboxHandle handle) throws SandboxException {
        return session(handle).snapshot();
    }

    @Override
    public void destroy(SandboxHandle handle) throws SandboxException {
        session(handle).destroy();
    }

    @Override
    public void close() {
        sessions.values().forEach(Session::destroyQuietly);
        executor.shutdownNow();
    }

    private Session session(SandboxHandle handle) throws SandboxException {
        Objects.requireNonNull(handle, "handle");
        Session session = sessions.get(handle.id());
        if (session == null) {
            throw new SandboxException("sandbox not found: " + handle.id());
        }
        return session;
    }

    private final class Session {
        private final SandboxHandle handle;
        private final SandboxSpec spec;
        private final Instant createdAt = Instant.now();
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder error = new StringBuilder();

        private volatile SandboxStatus status = SandboxStatus.STARTING;
        private volatile boolean outputLimitExceeded;
        private volatile long processId = -1;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile Integer exitCode;
        private volatile Process process;
        private volatile Path workDirectory;
        private volatile Future<?> outputReader;
        private volatile Future<?> errorReader;

        private Session(SandboxHandle handle, SandboxSpec spec) {
            this.handle = handle;
            this.spec = spec;
        }

        private void start() throws SandboxException {
            try {
                workDirectory = spec.workingDirectory() == null
                        ? Files.createTempDirectory("easycode-sandbox-")
                        : Files.createDirectories(spec.workingDirectory());
                ProcessBuilder builder = new ProcessBuilder(spec.command())
                        .directory(workDirectory.toFile())
                        .redirectErrorStream(false);
                builder.environment().putAll(spec.environment());
                process = builder.start();
                processId = process.pid();
                startedAt = Instant.now();
                status = SandboxStatus.RUNNING;

                outputReader = executor.submit(() -> readStream(process.getInputStream(), output, false));
                errorReader = executor.submit(() -> readStream(process.getErrorStream(), error, true));
                executor.submit(this::monitor);
            } catch (IOException | RuntimeException exception) {
                destroyProcessTree(process);
                closeProcessStreams(process);
                status = SandboxStatus.FAILED;
                finishedAt = Instant.now();
                cleanupDirectory();
                throw new SandboxException("failed to start sandbox " + handle.id(), exception);
            }
        }

        private void monitor() {
            try {
                if (!process.waitFor(spec.limits().timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    destroyProcessTree(process);
                    awaitReaders();
                    synchronized (this) {
                        if (status == SandboxStatus.RUNNING) {
                            status = SandboxStatus.TIMED_OUT;
                            finishedAt = Instant.now();
                        }
                    }
                } else {
                    exitCode = process.exitValue();
                    // The output readers can still discover an output-limit
                    // violation after the parent exits, so finalize status only
                    // after they have drained both streams.
                    destroyProcessTree(process);
                    awaitReaders();
                    synchronized (this) {
                        if (status == SandboxStatus.RUNNING) {
                            status = outputLimitExceeded
                                    ? SandboxStatus.OUTPUT_LIMIT
                                    : (exitCode == 0 ? SandboxStatus.SUCCEEDED : SandboxStatus.FAILED);
                            finishedAt = Instant.now();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                destroyProcessTree(process);
                awaitReaders();
            } finally {
                destroyProcessTree(process);
                closeProcessStreams(process);
                finishedAt = Instant.now();
                cleanupDirectory();
            }
        }

        private void destroy() {
            synchronized (this) {
                if (status == SandboxStatus.SUCCEEDED || status == SandboxStatus.FAILED
                        || status == SandboxStatus.TIMED_OUT
                        || status == SandboxStatus.OUTPUT_LIMIT
                        || status == SandboxStatus.DESTROYED) {
                    return;
                }
                status = SandboxStatus.DESTROYED;
            }
            if (process != null) {
                destroyProcessTree(process);
                closeProcessStreams(process);
            }
            finishedAt = Instant.now();
            cleanupDirectory();
        }

        private void destroyQuietly() {
            // Session.destroy() is idempotent and does not throw.
            destroy();
        }

        private synchronized SandboxInfo snapshot() {
            return new SandboxInfo(
                    handle,
                    status,
                    processId,
                    createdAt,
                    startedAt,
                    finishedAt,
                    exitCode,
                    bounded(output),
                    bounded(error));
        }

        private String bounded(StringBuilder value) {
            synchronized (value) {
                int max = (int) Math.min(spec.limits().maxOutputChars(), Integer.MAX_VALUE);
                if (value.length() <= max) {
                    return value.toString();
                }
                return value.substring(0, max);
            }
        }

        private void readStream(InputStream stream, StringBuilder target, boolean errorStream) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (target) {
                        if (target.length() + read > spec.limits().maxOutputChars()) {
                            outputLimitExceeded = true;
                            target.append(buffer, 0,
                                    (int) (spec.limits().maxOutputChars() - target.length()));
                            if (errorStream) {
                                error.append("sandbox stderr exceeded output limit\n");
                            } else {
                                error.append("sandbox stdout exceeded output limit\n");
                            }
                            destroyProcessTree(process);
                            return;
                        }
                        target.append(buffer, 0, read);
                    }
                }
            } catch (IOException exception) {
                synchronized (error) {
                    error.append("failed to read sandbox output: ")
                            .append(exception.getMessage()).append('\n');
                }
            }
        }

        private void awaitReaders() {
            awaitReader(outputReader);
            awaitReader(errorReader);
        }

        private void awaitReader(Future<?> reader) {
            if (reader == null) {
                return;
            }
            try {
                reader.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                reader.cancel(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reader.cancel(true);
            } catch (ExecutionException execution) {
                synchronized (error) {
                    Throwable cause = execution.getCause();
                    error.append("sandbox output reader failed: ")
                            .append(cause == null ? execution.getMessage() : cause.getMessage())
                            .append('\n');
                }
                reader.cancel(true);
            }
        }

        private void cleanupDirectory() {
            if (workDirectory == null || spec.workingDirectory() != null) {
                return;
            }
            try {
                if (Files.notExists(workDirectory)) {
                    return;
                }
                try (var paths = Files.walk(workDirectory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Defer deletion to the JVM; cleanup must not alter the result.
                            path.toFile().deleteOnExit();
                        }
                    });
                }
            } catch (IOException ignored) {
                // Defer deletion to the JVM; cleanup must not alter the result.
                workDirectory.toFile().deleteOnExit();
            }
        }
    }

    private static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
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

    private static void closeProcessStreams(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best effort release of process handles during cleanup.
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
