package com.easycode.tool.integration;

import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolFailureReason;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import com.easycode.tool.model.ToolResultStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContractIntegrationTest {
    @Test
    void definitionPreservesStableIdentityAndSchema() {
        ToolDefinition definition = new ToolDefinition(
                "test.echo", "Returns the supplied input", "{\"type\":\"string\"}");

        assertEquals("test.echo", definition.name());
        assertEquals("Returns the supplied input", definition.description());
        assertEquals("{\"type\":\"string\"}", definition.inputSchema());
    }

    @Test
    void registryResolvesListsAndInvokesTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        ToolInvocation invocation = ToolInvocation.create("test.echo", "hello");

        assertTrue(registry.resolve("test.echo").isPresent());
        assertEquals(List.of("test.echo"), registry.list().stream()
                .map(ToolDefinition::name).toList());

        ToolResult result = registry.invoke(invocation);
        assertEquals(invocation.callId(), result.callId());
        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertEquals("hello", result.output());
    }

    @Test
    void missingToolProducesStableFailure() {
        ToolInvocation invocation = ToolInvocation.create("missing.tool", "input");
        ToolResult result = new ToolRegistry().invoke(invocation);

        assertFalse(result.succeeded());
        assertEquals(ToolFailureReason.TOOL_NOT_FOUND, result.failureReason());
        assertTrue(result.error().contains("missing.tool"), result.error());
    }

    @Test
    void thrownToolExceptionIsNormalizedAtBoundary() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FailingTool());

        ToolResult result = registry.invoke(ToolInvocation.create("test.fail", "input"));

        assertEquals(ToolResultStatus.FAILURE, result.status());
        assertEquals(ToolFailureReason.EXECUTION_FAILURE, result.failureReason());
        assertEquals("expected test failure", result.error());
    }

    @Test
    void mismatchedToolResultCallIdIsNormalizedAtBoundary() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MismatchedResultTool());
        ToolInvocation invocation = ToolInvocation.create("test.mismatch", "input");

        ToolResult result = registry.invoke(invocation);

        assertEquals(invocation.callId(), result.callId());
        assertEquals(ToolResultStatus.FAILURE, result.status());
        assertEquals(ToolFailureReason.INTERNAL_ERROR, result.failureReason());
        assertEquals("tool returned a mismatched call id", result.error());
    }

    @Test
    void invalidDefinitionsAndInvocationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("Bad Name", "description", "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("test.valid", "", "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDefinition("test.valid", "description", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolInvocation(UUID.randomUUID(), "", "input"));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolInvocation(UUID.randomUUID(), "test.valid", null));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        assertThrows(IllegalArgumentException.class, () -> registry.register(new EchoTool()));
    }

    @Test
    void toolCoreDoesNotExposeExecutionOrSandboxImplementation() {
        String[] forbidden = {
                "com.easycode.execution",
                "com.easycode.runtime",
                "com.easycode.sandbox",
                "com.sun.jna",
                "java.io",
                "java.sql"
        };
        Class<?>[] publicTypes = {
                Tool.class,
                ToolRegistry.class,
                ToolDefinition.class,
                ToolInvocation.class,
                ToolResult.class
        };
        for (Class<?> type : publicTypes) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertNoForbidden(constructor.getParameterTypes(), forbidden);
            }
            for (Field field : type.getDeclaredFields()) {
                assertNoForbidden(new Class<?>[]{field.getType()}, forbidden);
            }
            for (Method method : type.getMethods()) {
                assertNoForbidden(method.getParameterTypes(), forbidden);
                assertNoForbidden(new Class<?>[]{method.getReturnType()}, forbidden);
            }
        }
    }

    private static void assertNoForbidden(Class<?>[] types, String[] forbidden) {
        for (Class<?> type : types) {
            String name = type.getName();
            assertTrue(java.util.Arrays.stream(forbidden).noneMatch(name::startsWith),
                    "Tool boundary exposes " + name);
        }
    }

    private static final class EchoTool implements Tool {
        private static final ToolDefinition DEFINITION = new ToolDefinition(
                "test.echo", "Returns the supplied input", "{\"type\":\"string\"}");

        @Override
        public ToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.success(invocation.callId(), invocation.input());
        }
    }

    private static final class FailingTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("test.fail", "Fails for boundary testing", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            throw new IllegalStateException("expected test failure");
        }
    }

    private static final class MismatchedResultTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("test.mismatch", "Returns a mismatched result", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.success(UUID.randomUUID(), "wrong correlation");
        }
    }
}
