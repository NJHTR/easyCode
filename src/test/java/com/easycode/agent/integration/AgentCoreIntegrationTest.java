package com.easycode.agent.integration;

import com.easycode.agent.api.AgentService;
import com.easycode.agent.adapter.jvm.JvmAgentExecutionAdapter;
import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.execution.api.ExecutionService;
import com.easycode.execution.host.HostExecutionBackend;
import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.sandbox.SandboxExecutionBackend;
import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.windows.WindowsJobObjectSandboxManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCoreIntegrationTest {
    @Test
    void agentRunsJvmWorkerOnHost() {
        try (ExecutionService execution = new ExecutionService(new HostExecutionBackend())) {
            AgentResult result = new AgentService(new JvmAgentExecutionAdapter(execution)).run(request(
                    "uppercase", "agent-host", ExecutionEnvironment.HOST, Duration.ofSeconds(5)));

            assertTrue(result.succeeded(), result.toString());
            assertEquals(AgentRunStatus.SUCCEEDED, result.run().status());
            assertEquals("AGENT-HOST", result.executionResult().stdout());
            assertNull(result.failureReason());
        }
    }

    @Test
    void agentPreservesExecutionFailure() {
        try (ExecutionService execution = new ExecutionService(new HostExecutionBackend())) {
            AgentResult result = new AgentService(new JvmAgentExecutionAdapter(execution)).run(request(
                    "unsupported-operation", "input", ExecutionEnvironment.HOST,
                    Duration.ofSeconds(5)));

            assertEquals(AgentRunStatus.FAILED, result.run().status());
            assertEquals(AgentFailureReason.EXECUTION_FAILURE, result.failureReason());
            assertFalse(result.succeeded());
            assertTrue(result.executionResult().failureMessage().contains("code 2"),
                    result.toString());
        }
    }

    @Test
    void agentMapsTimeout() {
        try (ExecutionService execution = new ExecutionService(new HostExecutionBackend())) {
            AgentResult result = new AgentService(new JvmAgentExecutionAdapter(execution)).run(request(
                    "sleep", "5000", ExecutionEnvironment.HOST, Duration.ofMillis(150)));

            assertEquals(AgentRunStatus.TIMED_OUT, result.run().status());
            assertEquals(AgentFailureReason.TIMEOUT, result.failureReason());
            assertEquals("TIMED_OUT", result.executionResult().status().name());
        }
    }

    @Test
    void invalidRequestIsRejectedAtAgentBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new AgentRequest(
                java.util.UUID.randomUUID(), "", "input", ExecutionEnvironment.HOST,
                Duration.ofSeconds(1)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void agentUsesExistingSandboxThroughExecutionBoundary() throws Exception {
        try (SandboxService sandbox = new SandboxService(new WindowsJobObjectSandboxManager());
             ExecutionService execution = new ExecutionService(new SandboxExecutionBackend(sandbox))) {
            AgentResult result = new AgentService(new JvmAgentExecutionAdapter(execution)).run(request(
                    "uppercase", "agent-sandbox", ExecutionEnvironment.SANDBOX,
                    Duration.ofSeconds(5)));

            assertTrue(result.succeeded(), result.toString());
            assertEquals("AGENT-SANDBOX", result.executionResult().stdout());
        }
    }

    @Test
    void agentPublicBoundaryDoesNotExposeSandboxImplementation() {
        String[] forbidden = {
                "com.easycode.sandbox.windows",
                "com.sun.jna",
                "com.easycode.runtime.jvm.SandboxRunner"
        };
        for (Constructor<?> constructor : AgentService.class.getConstructors()) {
            assertNoForbiddenType(constructor.getParameterTypes(), forbidden);
        }
        for (Field field : AgentService.class.getDeclaredFields()) {
            assertNoForbiddenType(new Class<?>[]{field.getType()}, forbidden);
        }
        for (Method method : AgentService.class.getMethods()) {
            assertNoForbiddenType(method.getParameterTypes(), forbidden);
            assertNoForbiddenType(new Class<?>[]{method.getReturnType()}, forbidden);
        }
    }

    @Test
    void agentServiceWorksWithANonJvmExecutionPort() {
        ExecutionResult executionResult = new ExecutionResult(
                java.util.UUID.randomUUID(),
                ExecutionStatus.SUCCEEDED,
                0,
                "PORT-OK",
                "",
                Duration.ZERO,
                com.easycode.execution.model.ExecutionTerminationReason.COMPLETED,
                "");
        AgentService service = new AgentService(request -> executionResult);

        AgentResult result = service.run(request(
                "any-operation", "any-input", ExecutionEnvironment.HOST, Duration.ofSeconds(1)));

        assertTrue(result.succeeded(), result.toString());
        assertEquals("PORT-OK", result.executionResult().stdout());
    }

    private static AgentRequest request(
            String action, String input, ExecutionEnvironment environment, Duration timeout) {
        return AgentRequest.create(action, input, environment, timeout);
    }

    private static void assertNoForbiddenType(Class<?>[] types, String[] forbidden) {
        for (Class<?> type : types) {
            String name = type.getName();
            assertTrue(Arrays.stream(forbidden).noneMatch(name::startsWith),
                    "Agent boundary exposes " + name);
        }
    }
}
