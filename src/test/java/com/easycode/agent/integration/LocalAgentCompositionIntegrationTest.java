package com.easycode.agent.integration;

import com.easycode.agent.composition.LocalAgentComposition;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.llm.model.LlmToolCall;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentCompositionIntegrationTest {
    @Test
    void compositionCanBeCreatedAndRunsTextThroughExistingOrchestrator() {
        RecordingProvider provider = new RecordingProvider(LlmResponse.text("hello"));
        LocalAgentComposition composition = new LocalAgentComposition(provider, new ToolRegistry(), 3);

        AgentResult result = composition.run(prompt());

        assertTrue(result.succeeded());
        assertEquals("hello", result.message());
        assertEquals(1, provider.calls);
    }

    @Test
    void compositionRunsToolCallThroughRegistryAccessAndContinues() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        LlmToolCall call = LlmToolCall.create("test.echo", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)),
                LlmResponse.text("done"));

        AgentResult result = new LocalAgentComposition(provider, registry, 3).run(prompt());

        assertTrue(result.succeeded());
        assertEquals("done", result.message());
        assertEquals(2, provider.calls);
        assertEquals("input", provider.requests.get(1).messages().get(2).content());
    }

    @Test
    void compositionTraceContainsStepsAndMatchesResult() {
        RecordingProvider provider = new RecordingProvider(LlmResponse.text("hello"));

        AgentExecution execution = new LocalAgentComposition(provider, new ToolRegistry(), 3)
                .runWithTrace(prompt());

        assertTrue(execution.result().succeeded());
        assertEquals(AgentRunStatus.SUCCEEDED, execution.trace().outcome());
        assertFalse(execution.trace().steps().isEmpty());
        assertEquals(AgentStepOutcome.COMPLETED, execution.trace().steps().get(0).outcome());
        assertEquals(execution.result().run().runId(), execution.trace().runId());
    }

    @Test
    void compositionBoundaryDoesNotExposeFrameworkOrImplementationTypes() {
        String[] forbidden = {
                "org.springframework.ai", "com.easycode.execution", "com.easycode.sandbox",
                "com.openai", "net.java.dev.jna", "com.easycode.tool.api.Tool"
        };
        for (java.lang.reflect.Constructor<?> constructor : LocalAgentComposition.class.getConstructors()) {
            assertNoForbidden(constructor.getParameterTypes(), forbidden);
        }
        for (java.lang.reflect.Method method : LocalAgentComposition.class.getMethods()) {
            assertNoForbidden(method.getParameterTypes(), forbidden);
            assertNoForbidden(new Class<?>[]{method.getReturnType()}, forbidden);
        }
    }

    private static void assertNoForbidden(Class<?>[] types, String[] forbidden) {
        for (Class<?> type : types) {
            assertTrue(java.util.Arrays.stream(forbidden)
                    .noneMatch(type.getName()::equals), type.getName());
        }
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create("fake-model", List.of(LlmMessage.user("hello")));
    }

    private static final class RecordingProvider implements LlmProvider {
        private final Queue<LlmResponse> responses = new ArrayDeque<>();
        private final List<com.easycode.llm.model.LlmRequest> requests = new ArrayList<>();
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
}
