package com.easycode.agent.application;

import com.easycode.agent.composition.LocalAgentComposition;
import com.easycode.agent.composition.LocalAgentSession;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.llm.adapter.springai.SpringAiLlmProvider;
import com.easycode.llm.adapter.springai.openai.OpenAiChatModelFactory;
import com.easycode.llm.adapter.springai.openai.OpenAiCompatibleModelConfig;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.model.LlmMessage;
import com.easycode.tool.api.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.io.PrintStream;

/**
 * Minimal headless entry boundary for one local Agent session.
 *
 * <p>The command-line entry point reads only explicit environment variables and
 * one prompt argument. It does not persist configuration, print credentials, or
 * add an application framework.</p>
 */
public final class LocalAgentApplication {
    static final String API_KEY_ENV = "EASYCODE_LLM_API_KEY";
    static final String MODEL_ENV = "EASYCODE_LLM_MODEL";
    static final String BASE_URL_ENV = "EASYCODE_LLM_BASE_URL";
    static final String MAX_STEPS_ENV = "EASYCODE_AGENT_MAX_STEPS";
    static final int DEFAULT_MAX_STEPS = 5;

    private LocalAgentApplication() {
    }

    /** Creates a local session from an already constructed provider. */
    public static LocalAgentSession openSession(
            LlmProvider provider, ToolRegistry toolRegistry, int maxSteps) {
        return new LocalAgentComposition(
                Objects.requireNonNull(provider, "provider"),
                Objects.requireNonNull(toolRegistry, "toolRegistry"),
                maxSteps).openSession();
    }

    /** Creates a local session backed by the OpenAI-compatible adapter. */
    public static LocalAgentSession openOpenAiSession(
            OpenAiCompatibleModelConfig modelConfig,
            ToolRegistry toolRegistry,
            int maxSteps) {
        Objects.requireNonNull(modelConfig, "modelConfig");
        return openSession(
                new SpringAiLlmProvider(OpenAiChatModelFactory.create(modelConfig)),
                toolRegistry,
                maxSteps);
    }

    /** Creates an OpenAI-compatible session from the documented environment. */
    public static LocalAgentSession openOpenAiSessionFromEnvironment(
            ToolRegistry toolRegistry) {
        String apiKey = requiredEnvironment(API_KEY_ENV);
        String model = requiredEnvironment(MODEL_ENV);
        String baseUrl = optionalEnvironment(BASE_URL_ENV)
                .orElse(OpenAiCompatibleModelConfig.DEFAULT_BASE_URL);
        int maxSteps = parseMaxSteps(optionalEnvironment(MAX_STEPS_ENV).orElse(null));
        return openOpenAiSession(
                new OpenAiCompatibleModelConfig(baseUrl, apiKey, model),
                toolRegistry,
                maxSteps);
    }

    /** Runs one prompt through the explicit environment-configured local path. */
    public static AgentExecution runOnceFromEnvironment(String prompt) {
        String model = requiredEnvironment(MODEL_ENV);
        LocalAgentSession session = openOpenAiSessionFromEnvironment(new ToolRegistry());
        return session.runWithTrace(AgentPromptRequest.create(
                model, List.of(LlmMessage.user(requirePrompt(prompt)))));
    }

    /** Runs one prompt using EASYCODE_LLM_* configuration and prints only its result. */
    public static void main(String[] args) {
        int exitCode = runCli(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /** Runs the one-shot CLI boundary without terminating the hosting JVM. */
    static int runCli(String[] args, PrintStream stdout, PrintStream stderr) {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        try {
            if (isHelpRequest(args)) {
                printHelp(stdout);
                return 0;
            }
            if (args == null || args.length != 1) {
                throw new IllegalArgumentException(
                        "usage: LocalAgentApplication <prompt>");
            }
            return reportExecution(runOnceFromEnvironment(args[0]), stdout, stderr);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            stderr.println("Local agent configuration error: "
                    + safeConfigurationMessage(exception));
            return 1;
        } catch (RuntimeException exception) {
            // Provider failures may contain request details or credentials; keep the
            // process diagnostic deliberately generic at this boundary.
            stderr.println("Local agent failed: "
                    + exception.getClass().getSimpleName());
            return 1;
        }
    }

    private static boolean isHelpRequest(String[] args) {
        return args != null && args.length == 1
                && ("--help".equals(args[0]) || "-h".equals(args[0]));
    }

    private static void printHelp(PrintStream stdout) {
        stdout.println("Usage: LocalAgentApplication <prompt>");
        stdout.println("Runs one prompt through the local Agent application.");
        stdout.println();
        stdout.println("Required environment variables:");
        stdout.println("  " + API_KEY_ENV);
        stdout.println("  " + MODEL_ENV);
        stdout.println("Optional environment variables:");
        stdout.println("  " + BASE_URL_ENV);
        stdout.println("  " + MAX_STEPS_ENV);
        stdout.println();
        stdout.println("Use -h or --help to show this help.");
    }

    public static int reportExecution(
            AgentExecution execution, PrintStream stdout, PrintStream stderr) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        if (execution.result().succeeded()) {
            stdout.println(execution.result().message());
            return 0;
        }
        stderr.println("Agent execution failed: "
                + execution.result().run().status()
                + " (" + execution.result().failureReason() + ")");
        return 1;
    }

    private static String safeConfigurationMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && (message.endsWith(" is not configured")
                || message.startsWith("usage:")
                || message.startsWith("prompt must not be blank")
                || message.startsWith(MAX_STEPS_ENV + " must be"))) {
            return message;
        }
        return "invalid local agent configuration";
    }

    private static String requiredEnvironment(String name) {
        return optionalEnvironment(name)
                .orElseThrow(() -> new IllegalStateException(
                        name + " is not configured"));
    }

    private static java.util.Optional<String> optionalEnvironment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank()
                ? java.util.Optional.empty()
                : java.util.Optional.of(value.trim());
    }

    private static int parseMaxSteps(String value) {
        if (value == null) {
            return DEFAULT_MAX_STEPS;
        }
        try {
            int maxSteps = Integer.parseInt(value);
            if (maxSteps <= 0) {
                throw new IllegalArgumentException(
                        MAX_STEPS_ENV + " must be positive");
            }
            return maxSteps;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    MAX_STEPS_ENV + " must be a positive integer", exception);
        }
    }

    private static String requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return prompt.trim();
    }
}
