package com.easycode.agent.integration;

import com.easycode.agent.application.LocalAgentApplication;
import com.easycode.agent.composition.LocalAgentSession;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.api.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalAgentApplicationIntegrationTest {
    @Test
    void offlineProviderCanBeWiredThroughApplicationIntoSession() {
        LocalAgentSession session = LocalAgentApplication.openSession(
                request -> LlmResponse.text("offline-ok"),
                new ToolRegistry(),
                3);

        var result = session.run(prompt());

        assertTrue(result.succeeded());
        assertEquals("offline-ok", result.message());
        assertEquals(1, session.executions().size());
    }

    @Test
    void openAiApplicationPathBuildsWithoutMakingARequest() {
        LocalAgentSession session = LocalAgentApplication.openOpenAiSession(
                new com.easycode.llm.adapter.springai.openai.OpenAiCompatibleModelConfig(
                        "https://example.com", "test-key", "test-model"),
                new ToolRegistry(),
                3);

        assertNotNull(session);
        assertNotNull(session.sessionId());
    }

    private static AgentPromptRequest prompt() {
        return AgentPromptRequest.create(
                "fake-model", List.of(LlmMessage.user("hello")));
    }
}
