package com.easycode.agent.integration;

import com.easycode.agent.api.AgentOrchestrator;
import com.easycode.agent.adapter.tool.RegistryAgentToolAccess;
import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.llm.model.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorIntegrationTest {
    @Test
    void directTextFinishesAfterOneModelCall() {
        RecordingProvider provider = new RecordingProvider(LlmResponse.text("hello"));
        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded());
        assertEquals("hello", result.message());
        assertEquals(1, provider.calls);
    }

    @Test
    void toolCallExecutesRealToolAndFeedsResultToNextCall() {
        LlmToolCall call = LlmToolCall.create("test.echo", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("final answer"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        assertEquals("final answer", result.message());
        assertEquals(2, provider.calls);
        assertEquals(2, provider.requests.get(0).tools().size());
        assertEquals("input", provider.requests.get(1).messages().get(1).content());
        assertEquals(call.callId(), provider.requests.get(1).messages().get(1).toolCallId());
    }

    @Test
    void toolFailureIsFedBackAndModelCanRecover() {
        LlmToolCall call = LlmToolCall.create("test.fail", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("recovered"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        assertEquals("recovered", result.message());
        assertTrue(provider.requests.get(1).messages().get(1).content().contains("EXECUTION_FAILURE"));
    }

    @Test
    void multipleToolCallsAreExecutedInReturnedOrder() {
        LlmToolCall first = LlmToolCall.create("test.echo", "first");
        LlmToolCall second = LlmToolCall.create("test.echo", "second");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(first, second)),
                LlmResponse.text("ordered"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        List<LlmMessage> messages = provider.requests.get(1).messages();
        assertEquals("first", messages.get(1).content());
        assertEquals("second", messages.get(2).content());
    }

    @Test
    void unknownToolIsNormalizedAndFedBack() {
        LlmToolCall call = LlmToolCall.create("missing.tool", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("handled"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        assertTrue(provider.requests.get(1).messages().get(1).content().contains("TOOL_NOT_FOUND"));
    }

    @Test
    void maxStepsStopsRepeatedToolCalls() {
        LlmToolCall call = LlmToolCall.create("test.echo", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)),
                LlmResponse.withToolCalls("", List.of(call)),
                LlmResponse.withToolCalls("", List.of(call)));

        AgentResult result = orchestrator(provider, 3).run(prompt());

        assertFalse(result.succeeded());
        assertEquals(AgentFailureReason.MAX_STEPS_REACHED, result.failureReason());
        assertEquals(3, provider.calls);
    }

    @Test
    void providerFailureBecomesAgentFailure() {
        AgentResult result = orchestrator(request -> { throw new LlmException("provider down"); }, 5)
                .run(prompt());

        assertFalse(result.succeeded());
        assertEquals(AgentFailureReason.LLM_FAILURE, result.failureReason());
    }

    @Test
    void emptyResponseBecomesInvalidResponse() {
        AgentResult result = orchestrator(request -> new LlmResponse("", List.of()), 5)
                .run(prompt());

        assertFalse(result.succeeded());
        assertEquals(AgentFailureReason.INVALID_RESPONSE, result.failureReason());
    }

    @Test
    void orchestratorPublicBoundaryDoesNotDependOnFrameworkOrRuntime() {
        String[] forbidden = {
                "org.springframework.ai", "com.easycode.execution", "com.easycode.sandbox",
                "com.easycode.runtime", "com.openai", "dev.langchain4j"
        };
        for (java.lang.reflect.Constructor<?> constructor
                : AgentOrchestrator.class.getConstructors()) {
            assertNoForbidden(constructor.getParameterTypes(), forbidden);
        }
        for (java.lang.reflect.Method method : AgentOrchestrator.class.getMethods()) {
            assertNoForbidden(method.getParameterTypes(), forbidden);
            assertNoForbidden(new Class<?>[]{method.getReturnType()}, forbidden);
        }
    }

    private static void assertNoForbidden(Class<?>[] types, String[] forbidden) {
        for (Class<?> type : types) {
            assertTrue(java.util.Arrays.stream(forbidden)
                    .noneMatch(type.getName()::startsWith), type.getName());
        }
    }

    private static AgentOrchestrator orchestrator(LlmProvider provider, int maxSteps) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new FailingTool());
        return new AgentOrchestrator(provider, new RegistryAgentToolAccess(registry), maxSteps);
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create("fake-model", List.of(LlmMessage.user("hello")));
    }

    private static final class RecordingProvider implements LlmProvider {
        private final Queue<LlmResponse> responses = new ArrayDeque<>();
        private final List<com.easycode.llm.model.LlmRequest> requests = new java.util.ArrayList<>();
        private int calls;

        private RecordingProvider(LlmResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmResponse generate(com.easycode.llm.model.LlmRequest request) {
            requests.add(request);
            calls++;
            return responses.remove();
        }
    }

    private static final class EchoTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("test.echo", "Echo", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.success(invocation.callId(), invocation.input());
        }
    }

    private static final class FailingTool implements Tool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("test.fail", "Fail", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            throw new IllegalStateException("test tool failed");
        }
    }
}
