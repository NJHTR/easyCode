package com.easycode.agent.integration;

import com.easycode.agent.api.AgentOrchestrator;
import com.easycode.agent.adapter.tool.RegistryAgentToolAccess;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.agent.model.AgentStepTrace;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.adapter.springai.SpringAiLlmProvider;
import com.easycode.llm.adapter.springai.openai.OpenAiChatModelFactory;
import com.easycode.llm.adapter.springai.openai.OpenAiCompatibleModelConfig;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.api.ToolRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Explicit external verification of the real Agent Run Trace. */
@Tag("real-llm")
class RealAgentRunTraceSmokeTest {
    @Test
    void realAgentRunProducesCompleteTrace() {
        assumeTrue(isEnabled(), "REAL_AGENT_TRACE_SMOKE=NOT_RUN");
        String apiKey = environment("EASYCODE_LLM_API_KEY");
        String model = environment("EASYCODE_LLM_MODEL");
        assumeTrue(apiKey != null && model != null, "REAL_AGENT_TRACE_SMOKE=NOT_RUN");

        String baseUrl = environment("EASYCODE_LLM_BASE_URL");
        if (baseUrl == null) {
            baseUrl = OpenAiCompatibleModelConfig.DEFAULT_BASE_URL;
        }

        RealAgentToolCallingSmokeTest.AddTool addTool =
                new RealAgentToolCallingSmokeTest.AddTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(addTool);
        LlmProvider provider;
        try {
            provider = new SpringAiLlmProvider(OpenAiChatModelFactory.create(
                    new OpenAiCompatibleModelConfig(baseUrl, apiKey, model)));
        } catch (IllegalArgumentException exception) {
            fail("CONFIGURATION_ERROR");
            return;
        }

        AgentExecution execution = new AgentOrchestrator(
                provider, new RegistryAgentToolAccess(registry), 5).runWithTrace(
                        AgentPromptRequest.create(model, List.of(LlmMessage.user(
                                "Use the add tool to calculate 2 + 3. "
                                        + "Do not calculate it yourself. You must call the add tool. "
                                        + "After receiving the tool result, reply with the final result."))));

        assertTrue(execution.result().succeeded(), "REAL_AGENT_MODEL_ERROR");
        assertEquals(AgentRunStatus.SUCCEEDED, execution.trace().outcome());
        assertEquals(execution.result().run().runId(), execution.trace().runId());
        assertEquals(execution.result().run().requestId(), execution.trace().requestId());
        assertFalse(execution.trace().steps().isEmpty());

        AgentStepTrace first = execution.trace().steps().get(0);
        assertEquals(1, first.stepNumber());
        assertEquals(model, first.model());
        assertTrue(first.messageCount() > 0);
        assertTrue(first.availableToolCount() > 0);
        assertTrue(first.toolCallCount() > 0);
        assertTrue(first.toolObservations().stream().anyMatch(observation ->
                "add".equals(observation.toolName())
                        && observation.callId() != null
                        && observation.succeeded()
                        && observation.inputPresent()
                        && observation.outputPresent()));
        assertEquals(1, addTool.invocationCount);
        assertEquals(2, addTool.lastA);
        assertEquals(3, addTool.lastB);
        assertEquals("5", addTool.lastOutput);

        if (execution.trace().steps().size() >= 2) {
            AgentStepTrace second = execution.trace().steps().get(1);
            assertEquals(2, second.stepNumber());
            assertEquals(0, second.toolCallCount());
            assertTrue(second.responseContentPresent());
            assertEquals(AgentStepOutcome.COMPLETED, second.outcome());
        }

        for (AgentStepTrace step : execution.trace().steps()) {
            assertNotNull(step.startedAt());
            assertNotNull(step.completedAt());
            assertTrue(!step.duration().isNegative());
        }

        String traceText = execution.trace().toString();
        assertFalse(traceText.contains("Authorization"));
        assertFalse(traceText.contains("Bearer"));
        assertFalse(traceText.contains("ChatResponse"));
        assertFalse(traceText.contains("ChatModel"));
        assertFalse(traceText.contains("ToolCallback"));
        String configuredKey = environment("EASYCODE_LLM_API_KEY");
        assertTrue(configuredKey == null || !traceText.contains(configuredKey));
    }

    private static boolean isEnabled() {
        return "true".equalsIgnoreCase(environment("EASYCODE_REAL_LLM_TEST"));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
