package com.easycode.execution.runtime.jvm;

import com.easycode.execution.model.ExecutionRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds execution requests for the JDK's real javac and java commands. */
public final class JdkRuntime {
    private static final String FILE_ENCODING = "UTF-8";

    public ExecutionRequest compile(JavaCompilationSpec spec) {
        Objects.requireNonNull(spec, "spec");
        List<String> command = new ArrayList<>();
        command.add(executable(spec.javaHome(), "javac"));
        command.add("-encoding");
        command.add(FILE_ENCODING);
        command.add("-d");
        command.add(absolute(spec.outputDirectory()));
        if (!spec.classpath().isEmpty()) {
            command.add("-cp");
            command.add(classpath(spec.classpath()));
        }
        spec.sourceFiles().stream()
                .map(JdkRuntime::absolute)
                .forEach(command::add);
        return new ExecutionRequest(
                UUID.randomUUID(), command, spec.workingDirectory(),
                spec.environmentVariables(), spec.timeout(), spec.executionEnvironment(),
                spec.maxOutputChars(), spec.sandboxPolicy());
    }

    public ExecutionRequest launch(JavaLaunchSpec spec) {
        Objects.requireNonNull(spec, "spec");
        List<String> command = new ArrayList<>();
        command.add(executable(spec.javaHome(), "java"));
        command.add("-Dfile.encoding=" + FILE_ENCODING);
        if (!spec.classpath().isEmpty()) {
            command.add("-cp");
            command.add(classpath(spec.classpath()));
        }
        command.add(spec.mainClass());
        command.addAll(spec.programArguments());
        return new ExecutionRequest(
                UUID.randomUUID(), command, spec.workingDirectory(),
                spec.environmentVariables(), spec.timeout(), spec.executionEnvironment(),
                spec.maxOutputChars(), spec.sandboxPolicy());
    }

    private static String executable(Path javaHome, String name) {
        Path home = javaHome == null
                ? Path.of(System.getProperty("java.home")) : javaHome;
        String executable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe" : name;
        return home.resolve("bin").resolve(executable).toAbsolutePath().normalize().toString();
    }

    private static String classpath(List<Path> entries) {
        return entries.stream()
                .map(JdkRuntime::absolute)
                .collect(Collectors.joining(java.io.File.pathSeparator));
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }
}
