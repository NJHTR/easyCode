package com.easycode.agent.integration;

import com.easycode.agent.adapter.tool.RegistryAgentToolAccess;
import com.easycode.agent.api.AgentToolAccess;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolFailureReason;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolAccessIntegrationTest {
    @Test
    void agentDiscoversDefinitionsButNotToolImplementations() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        AgentToolAccess access = new RegistryAgentToolAccess(registry);

        List<ToolDefinition> definitions = access.listTools();

        assertEquals(List.of("test.echo"), definitions.stream()
                .map(ToolDefinition::name).toList());
        assertTrue(access.resolveTool("test.echo").isPresent());
        assertTrue(access.resolveTool("missing.tool").isEmpty());
        assertFalse(definitions.stream().anyMatch(Tool.class::isInstance));
    }

    @Test
    void agentInvokesToolThroughAccessBoundary() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        AgentToolAccess access = new RegistryAgentToolAccess(registry);
        ToolInvocation invocation = ToolInvocation.create("test.echo", "from-agent");

        ToolResult result = access.invoke(invocation);

        assertTrue(result.succeeded(), result.toString());
        assertEquals("from-agent", result.output());
        assertEquals(invocation.callId(), result.callId());
    }

    @Test
    void agentReadsNormalizedToolFailure() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FailingTool());
        AgentToolAccess access = new RegistryAgentToolAccess(registry);

        ToolResult result = access.invoke(ToolInvocation.create("test.fail", "input"));

        assertFalse(result.succeeded());
        assertEquals(ToolFailureReason.EXECUTION_FAILURE, result.failureReason());
        assertEquals("tool failed internally", result.error());
    }

    @Test
    void unknownToolReturnsNormalizedFailure() {
        AgentToolAccess access = new RegistryAgentToolAccess(new ToolRegistry());

        ToolResult result = access.invoke(ToolInvocation.create("missing.tool", "input"));

        assertEquals(ToolFailureReason.TOOL_NOT_FOUND, result.failureReason());
    }

    @Test
    void accessBoundaryHasNoRegistrationOperationsOrImplementationReturnTypes() {
        for (Method method : AgentToolAccess.class.getMethods()) {
            assertFalse(method.getName().equals("register"), method.toString());
            assertFalse(method.getName().equals("unregister"), method.toString());
            assertFalse(method.getName().equals("replace"), method.toString());
            assertFalse(method.getReturnType() == Tool.class, method.toString());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalse(parameter == Tool.class, method.toString());
                assertFalse(parameter == ToolRegistry.class, method.toString());
            }
        }
        for (Constructor<?> constructor : RegistryAgentToolAccess.class.getConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalse(parameter == Tool.class, constructor.toString());
            }
        }
    }

    private static final class EchoTool implements Tool {
        private static final ToolDefinition DEFINITION = new ToolDefinition(
                "test.echo", "Returns input", "{\"type\":\"string\"}");

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
            return new ToolDefinition("test.fail", "Fails", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            throw new IllegalStateException("tool failed internally");
        }
    }
}
