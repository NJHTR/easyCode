package com.easycode.execution.runtime.jvm;

import com.easycode.execution.api.ExecutionService;
import com.easycode.execution.host.HostExecutionBackend;
import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;
import com.easycode.sandbox.model.SandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkRuntimeIntegrationTest {
    @Test
    void compilesAndRunsUserProvidedJavaSource(@TempDir Path project) throws Exception {
        Path source = project.resolve("src/example/Hello.java");
        Path classes = project.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, "package example;\n"
                + "public class Hello {\n"
                + "  public static void main(String[] args) {\n"
                + "    System.out.print(System.getenv(\"EASYCODE_VALUE\") + \"|\" + args[0]);\n"
                + "  }\n"
                + "}\n");

        JdkRuntime runtime = new JdkRuntime();
        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult compile = service.execute(runtime.compile(new JavaCompilationSpec(
                    null, List.of(source), classes, List.of(), project, Map.of(),
                    Duration.ofSeconds(10), ExecutionEnvironment.HOST, 4096,
                    SandboxPolicy.defaults())));

            assertEquals(ExecutionStatus.SUCCEEDED, compile.status(), compile.toString());
            assertTrue(Files.exists(classes.resolve("example/Hello.class")));

            ExecutionResult run = service.execute(runtime.launch(new JavaLaunchSpec(
                    null, "example.Hello", List.of(classes), List.of("argument"), project,
                    Map.of("EASYCODE_VALUE", "value"), Duration.ofSeconds(5),
                    ExecutionEnvironment.HOST, 4096, SandboxPolicy.defaults())));

            assertEquals(ExecutionStatus.SUCCEEDED, run.status(), run.toString());
            assertEquals("value|argument", run.stdout());
        }
    }

    @Test
    void exposesRealCompilerFailure(@TempDir Path project) throws Exception {
        Path source = project.resolve("Broken.java");
        Path classes = project.resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(source, "public class Broken { missing syntax }\n");

        try (ExecutionService service = new ExecutionService(new HostExecutionBackend())) {
            ExecutionResult result = service.execute(new JdkRuntime().compile(
                    new JavaCompilationSpec(null, List.of(source), classes, List.of(), project,
                            Map.of(), Duration.ofSeconds(10), ExecutionEnvironment.HOST, 4096,
                            SandboxPolicy.defaults())));

            assertEquals(ExecutionStatus.FAILED, result.status(), result.toString());
            assertEquals(ExecutionTerminationReason.NON_ZERO_EXIT, result.terminationReason());
            assertFalse(result.stderr().isBlank());
            assertFalse(Files.exists(classes.resolve("Broken.class")));
        }
    }
}
