package com.easycode.agent.integration;

import com.easycode.agent.api.AgentOrchestrator;
import com.easycode.agent.adapter.tool.RegistryAgentToolAccess;
import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.agent.model.AgentStepTrace;
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
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorIntegrationTest {
    @Test
    void directResponseTraceContainsOneCompletedStep() {
        RecordingProvider provider = new RecordingProvider(LlmResponse.text("hello"));
        AgentExecution execution = orchestrator(provider, 5).runWithTrace(prompt());

        assertTrue(execution.result().succeeded());
        assertEquals(1, execution.trace().steps().size());
        assertEquals(AgentStepOutcome.COMPLETED, execution.trace().steps().get(0).outcome());
    }

    @Test
    void toolTraceContainsInvocationAndResultBeforeFinalStep() {
        LlmToolCall call = LlmToolCall.create("test.echo", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("done"));
        AgentExecution execution = orchestrator(provider, 5).runWithTrace(prompt());

        assertEquals(2, execution.trace().steps().size());
        assertEquals(AgentStepOutcome.TOOL_CALLS, execution.trace().steps().get(0).outcome());
        assertEquals(1, execution.trace().steps().get(0).toolObservations().size());
        assertTrue(execution.trace().steps().get(0).toolObservations().get(0).succeeded());
        assertEquals(AgentStepOutcome.COMPLETED, execution.trace().steps().get(1).outcome());
    }

    @Test
    void multipleToolTracePreservesToolOrder() {
        LlmToolCall first = LlmToolCall.create("test.echo", "first");
        LlmToolCall second = LlmToolCall.create("test.echo", "second");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(first, second)), LlmResponse.text("done"));
        AgentExecution execution = orchestrator(provider, 5)
                .runWithTrace(prompt());

        assertEquals(List.of("first", "second"), provider.requests.get(1).messages().subList(2, 4)
                .stream().map(LlmMessage::content).toList());
        assertEquals(List.of("test.echo", "test.echo"), execution.trace().steps().get(0)
                .toolObservations().stream().map(AgentStepTrace.ToolObservation::toolName).toList());
    }

    @Test
    void failureTraceRecordsToolFailure() {
        LlmToolCall call = LlmToolCall.create("test.fail", "input");
        AgentExecution execution = orchestrator(new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("done")), 5)
                .runWithTrace(prompt());

        assertFalse(execution.trace().steps().get(0).toolObservations().get(0).succeeded());
        assertEquals(com.easycode.tool.model.ToolFailureReason.EXECUTION_FAILURE,
                execution.trace().steps().get(0).toolObservations().get(0).failureReason());
    }

    @Test
    void unknownToolTraceRecordsNotFound() {
        LlmToolCall call = LlmToolCall.create("missing.tool", "input");
        AgentExecution execution = orchestrator(new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("done")), 5)
                .runWithTrace(prompt());

        assertEquals(com.easycode.tool.model.ToolFailureReason.TOOL_NOT_FOUND,
                execution.trace().steps().get(0).toolObservations().get(0).failureReason());
    }

    @Test
    void llmFailureTraceRecordsFailureOutcome() {
        AgentExecution execution = orchestrator(request -> {
            throw new LlmException("provider down");
        }, 5).runWithTrace(prompt());

        assertEquals(AgentFailureReason.LLM_FAILURE, execution.trace().failureReason());
        assertEquals(AgentStepOutcome.LLM_FAILURE, execution.trace().steps().get(0).outcome());
    }

    @Test
    void maxStepsTraceRecordsBoundary() {
        LlmToolCall call = LlmToolCall.create("test.echo", "input");
        AgentExecution execution = orchestrator(new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call))), 1).runWithTrace(prompt());

        assertEquals(AgentFailureReason.MAX_STEPS_REACHED, execution.trace().failureReason());
        assertEquals(AgentStepOutcome.MAX_STEPS_REACHED,
                execution.trace().steps().get(0).outcome());
    }

    @Test
    void emptyResponseTraceRecordsInvalidResponse() {
        AgentExecution execution = orchestrator(request -> new LlmResponse("", List.of()), 5)
                .runWithTrace(prompt());

        assertEquals(AgentFailureReason.INVALID_RESPONSE, execution.trace().failureReason());
        assertEquals(AgentStepOutcome.INVALID_RESPONSE,
                execution.trace().steps().get(0).outcome());
    }

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
        assertEquals("input", provider.requests.get(1).messages().get(2).content());
        assertEquals(call.callId(), provider.requests.get(1).messages().get(2).toolCallId());
    }

    @Test
    void toolFailureIsFedBackAndModelCanRecover() {
        LlmToolCall call = LlmToolCall.create("test.fail", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("recovered"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        assertEquals("recovered", result.message());
        assertTrue(provider.requests.get(1).messages().get(2).content().contains("EXECUTION_FAILURE"));
    }

    @Test
    void oversizedToolOutputIsBoundedBeforeNextModelCall() {
        ToolRegistry registry = new ToolRegistry();
        String largeOutput = "x".repeat(100_000);
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test.large", "Large output", "{}");
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.success(invocation.callId(), largeOutput);
            }
        });
        LlmToolCall call = LlmToolCall.create("test.large", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("done"));

        AgentExecution execution = new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5).runWithTrace(prompt());

        assertTrue(execution.result().succeeded(), execution.result().toString());
        String toolMessage = provider.requests.get(1).messages().get(2).content();
        assertEquals(32_768, toolMessage.length());
        assertTrue(toolMessage.endsWith("[tool output truncated]"));
        assertEquals(100_000, execution.trace().steps().get(0)
                .toolObservations().get(0).outputLength());
    }

    @Test
    void blockingToolTimesOutAndFeedsStructuredFailureBackToModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test.blocking", "Blocking tool", "{}");
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw new RuntimeException("tool interrupted", exception);
                }
                return ToolResult.success(invocation.callId(), "unreachable");
            }
        });
        LlmToolCall call = LlmToolCall.create("test.blocking", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("recovered"));

        AgentExecution execution = new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5)
                .runWithTrace(AgentPromptRequest.create(
                        "fake-model", List.of(LlmMessage.user("hello")), Duration.ofMillis(100)));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(execution.result().succeeded(), execution.result().toString());
        assertEquals("recovered", execution.result().message());
        assertEquals(com.easycode.tool.model.ToolFailureReason.TIMEOUT,
                execution.trace().steps().get(0).toolObservations().get(0).failureReason());
        assertTrue(provider.requests.get(1).messages().get(2).content().contains("TIMEOUT"));
    }

    @Test
    void interruptingBlockingToolCancelsTheAgentRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test.interruptible", "Interruptible tool", "{}");
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw new RuntimeException("tool interrupted", exception);
                }
                return ToolResult.success(invocation.callId(), "unreachable");
            }
        });
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(
                        LlmToolCall.create("test.interruptible", "input"))));
        AtomicReference<AgentExecution> execution = new AtomicReference<>();
        Thread runner = new Thread(() -> execution.set(new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5)
                .runWithTrace(AgentPromptRequest.create(
                        "fake-model", List.of(LlmMessage.user("hello")), Duration.ofSeconds(5)))));
        runner.start();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        runner.interrupt();
        runner.join(1000);

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertFalse(runner.isAlive());
        assertEquals(com.easycode.agent.model.AgentRunStatus.CANCELLED,
                execution.get().result().run().status());
        assertEquals(AgentFailureReason.CANCELLED, execution.get().result().failureReason());
        assertEquals(AgentStepOutcome.CANCELLED, execution.get().trace().steps().get(0).outcome());
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
        assertEquals("first", messages.get(2).content());
        assertEquals("second", messages.get(3).content());
    }

    @Test
    void unknownToolIsNormalizedAndFedBack() {
        LlmToolCall call = LlmToolCall.create("missing.tool", "input");
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(call)), LlmResponse.text("handled"));

        AgentResult result = orchestrator(provider, 5).run(prompt());

        assertTrue(result.succeeded(), result.toString());
        assertTrue(provider.requests.get(1).messages().get(2).content().contains("TOOL_NOT_FOUND"));
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
    void excessiveToolCallsFailBeforeAnyToolExecution() {
        List<LlmToolCall> calls = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> LlmToolCall.create("test.echo", "input-" + index))
                .toList();
        int[] toolCalls = {0};
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool(() -> toolCalls[0]++));
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", calls));

        AgentExecution execution = new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5)
                .runWithTrace(prompt());

        assertFalse(execution.result().succeeded());
        assertEquals(AgentFailureReason.MAX_TOOL_CALLS_REACHED,
                execution.result().failureReason());
        assertEquals(AgentStepOutcome.MAX_TOOL_CALLS_REACHED,
                execution.trace().steps().get(0).outcome());
        assertEquals(0, toolCalls[0]);
        assertEquals(1, provider.calls);
    }

    @Test
    void duplicateToolCallIdsFailBeforeAnyToolExecution() {
        UUID callId = UUID.randomUUID();
        LlmToolCall first = new LlmToolCall(callId, "test.echo", "first");
        LlmToolCall duplicate = new LlmToolCall(callId, "test.echo", "second");
        int[] toolCalls = {0};
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool(() -> toolCalls[0]++));
        RecordingProvider provider = new RecordingProvider(
                LlmResponse.withToolCalls("", List.of(first, duplicate)));

        AgentExecution execution = new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5)
                .runWithTrace(prompt());

        assertFalse(execution.result().succeeded());
        assertEquals(AgentFailureReason.INVALID_RESPONSE,
                execution.result().failureReason());
        assertEquals(AgentStepOutcome.INVALID_RESPONSE,
                execution.trace().steps().get(0).outcome());
        assertEquals(0, toolCalls[0]);
        assertEquals(1, provider.calls);
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
    void blockingProviderTimesOutWithoutInvokingTools() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        int[] toolCalls = {0};
        LlmProvider provider = request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw new LlmException("provider interrupted", exception);
            }
            return LlmResponse.withToolCalls("", List.of(LlmToolCall.create("test.echo", "never")));
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool(() -> toolCalls[0]++));
        AgentExecution execution = new AgentOrchestrator(provider,
                new RegistryAgentToolAccess(registry), 5)
                .runWithTrace(AgentPromptRequest.create("fake-model",
                        List.of(LlmMessage.user("hello")), Duration.ofMillis(100)));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(com.easycode.agent.model.AgentRunStatus.TIMED_OUT,
                execution.result().run().status());
        assertEquals(AgentFailureReason.TIMEOUT, execution.result().failureReason());
        assertEquals(AgentStepOutcome.TIMEOUT, execution.trace().steps().get(0).outcome());
        assertEquals(0, toolCalls[0]);
    }

    @Test
    void interruptionBecomesCancelledRunInsteadOfLlmFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch providerInterrupted = new CountDownLatch(1);
        LlmProvider provider = request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                providerInterrupted.countDown();
                throw new LlmException("provider interrupted", exception);
            }
            return LlmResponse.text("unreachable");
        };
        AgentOrchestrator orchestrator = orchestrator(provider, 5);
        AtomicReference<AgentExecution> execution = new AtomicReference<>();
        Thread runner = new Thread(() -> execution.set(orchestrator.runWithTrace(prompt())));
        runner.start();

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        runner.interrupt();
        runner.join(1000);

        assertTrue(providerInterrupted.await(1, TimeUnit.SECONDS));
        assertFalse(runner.isAlive());
        assertEquals(com.easycode.agent.model.AgentRunStatus.CANCELLED,
                execution.get().result().run().status());
        assertEquals(AgentFailureReason.CANCELLED, execution.get().result().failureReason());
        assertEquals(AgentStepOutcome.CANCELLED, execution.get().trace().steps().get(0).outcome());
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
        private final Runnable onExecute;

        private EchoTool() {
            this(() -> { });
        }

        private EchoTool(Runnable onExecute) {
            this.onExecute = onExecute;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("test.echo", "Echo", "{}");
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            onExecute.run();
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
