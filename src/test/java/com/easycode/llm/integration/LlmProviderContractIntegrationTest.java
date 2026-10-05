package com.easycode.llm.integration;

import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmMessageRole;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import com.easycode.llm.model.LlmToolCall;
import com.easycode.agent.api.AgentToolAccess;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderContractIntegrationTest {
    @Test
    void requestExpressesModelAndMessages() {
        LlmRequest request = LlmRequest.of("fake-model", List.of(
                LlmMessage.system("be concise"),
                LlmMessage.user("hello")));

        assertEquals("fake-model", request.model());
        assertEquals(2, request.messages().size());
        assertEquals(LlmMessageRole.USER, request.messages().get(1).role());
        assertEquals("hello", request.messages().get(1).content());
        assertTrue(request.tools().isEmpty());
    }

    @Test
    void requestExposesToolDefinitionsWithoutToolImplementations() {
        ToolDefinition definition = new ToolDefinition(
                "test.echo", "Echoes text", "{\"type\":\"object\"}");

        LlmRequest request = LlmRequest.of(
                "fake-model", List.of(LlmMessage.user("hello")), List.of(definition));

        assertEquals(List.of(definition), request.tools());
        assertEquals("test.echo", request.tools().get(0).name());
        assertEquals("Echoes text", request.tools().get(0).description());
        assertEquals("{\"type\":\"object\"}", request.tools().get(0).inputSchema());
    }

    @Test
    void requestDoesNotExposeToolImplementationsOrRegistries() {
        Class<?>[] forbidden = {
                Tool.class,
                ToolRegistry.class,
                AgentToolAccess.class,
                ToolInvocation.class,
                ToolResult.class
        };

        for (Field field : LlmRequest.class.getDeclaredFields()) {
            assertFalse(java.util.Arrays.asList(forbidden).contains(field.getType()),
                    "LlmRequest exposes " + field.getType().getName());
        }
        for (Method method : LlmProvider.class.getMethods()) {
            assertNoForbidden(method.getParameterTypes(), forbidden);
            assertNoForbidden(new Class<?>[]{method.getReturnType()}, forbidden);
        }
    }

    @Test
    void providerReturnsNormalTextResponse() throws LlmException {
        LlmProvider provider = request -> LlmResponse.text("model answer");

        LlmResponse response = provider.generate(request());

        assertEquals("model answer", response.content());
        assertTrue(response.toolCalls().isEmpty());
    }

    @Test
    void responseCarriesToolCallWithoutExecutingIt() throws LlmException {
        LlmToolCall call = LlmToolCall.create("test.echo", "{\"value\":\"hello\"}");
        LlmProvider provider = request -> LlmResponse.withToolCalls("", List.of(call));

        LlmResponse response = provider.generate(request());

        assertEquals(1, response.toolCalls().size());
        assertEquals(call.callId(), response.toolCalls().get(0).callId());
        assertEquals("test.echo", response.toolCalls().get(0).toolName());
        assertEquals("{\"value\":\"hello\"}", response.toolCalls().get(0).arguments());
    }

    @Test
    void providerFailureUsesCoreExceptionBoundary() {
        LlmProvider provider = request -> {
            try {
                throw new FakeProviderException("provider unavailable");
            } catch (FakeProviderException exception) {
                throw new LlmException("LLM provider call failed", exception);
            }
        };

        LlmException exception = assertThrows(
                LlmException.class, () -> provider.generate(request()));

        assertEquals("LLM provider call failed", exception.getMessage());
        assertTrue(exception.getCause() instanceof FakeProviderException);
    }

    @Test
    void invalidRequestsAndResponsesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmRequest("", List.of(LlmMessage.user("hello"))));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmRequest("fake-model", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmMessage(LlmMessageRole.USER, null));
        assertEquals("", new LlmResponse("", List.of()).content());
        assertThrows(IllegalArgumentException.class,
                () -> new LlmToolCall(java.util.UUID.randomUUID(), "", "{}"));
    }

    @Test
    void llmCorePublicBoundaryDoesNotDependOnOtherRuntimeLayers() {
        String[] forbidden = {
                "com.easycode.agent",
                "com.easycode.execution",
                "com.easycode.runtime",
                "com.easycode.sandbox",
                "org.springframework.ai",
                "dev.langchain4j",
                "com.openai",
                "io.mcp"
        };
        Class<?>[] publicTypes = {
                LlmProvider.class,
                LlmRequest.class,
                LlmResponse.class,
                LlmMessage.class,
                LlmToolCall.class,
                LlmException.class
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

    private static void assertNoForbidden(Class<?>[] types, Class<?>[] forbidden) {
        for (Class<?> type : types) {
            assertFalse(java.util.Arrays.asList(forbidden).contains(type),
                    "LLM boundary exposes " + type.getName());
        }
    }

    @Test
    void toolCallAndToolInvocationRemainDifferentTypes() {
        assertFalse(com.easycode.tool.model.ToolInvocation.class
                .isAssignableFrom(LlmToolCall.class));
        assertFalse(LlmToolCall.class
                .isAssignableFrom(com.easycode.tool.model.ToolInvocation.class));
    }

    private static LlmRequest request() {
        return LlmRequest.of("fake-model", List.of(LlmMessage.user("hello")));
    }

    private static void assertNoForbidden(Class<?>[] types, String[] forbidden) {
        for (Class<?> type : types) {
            String name = type.getName();
            assertTrue(java.util.Arrays.stream(forbidden).noneMatch(name::startsWith),
                    "LLM boundary exposes " + name);
        }
    }

    private static final class FakeProviderException extends Exception {
        private FakeProviderException(String message) {
            super(message);
        }
    }
}
