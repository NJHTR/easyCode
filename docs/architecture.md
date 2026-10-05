# easyCode Architecture Boundary

easyCode is being built as a local, general-purpose Agent system. The current
repository contains infrastructure slices, not the complete product.

## Current chain

```text
Agent
  -> Execution
      -> Runtime
          -> Execution Environment
              -> Host or Sandbox
```

Agent capabilities are parallel boundaries:

```text
                 Agent
                /     \
               /       \
          Execution     Tool
             |            |
          Runtime      Tool implementation
             |
       Host / Sandbox
```

The Agent-facing Tool boundary is intentionally narrower than the application
owned registry:

```text
Agent
  -> AgentToolAccess
      -> ToolDefinition / ToolInvocation / ToolResult
          -> application-owned ToolRegistry
```

`AgentToolAccess` exposes only `listTools`, `resolveTool`, and `invoke`. It does
not expose Tool implementations or registration operations. Registration is
owned by the composition root that builds the `ToolRegistry`.

The model boundary is separate from both Tool and Execution:

```text
Agent
  -> AgentToolAccess
      -> ToolDefinition
          -> LlmRequest
              -> LlmProvider
                  -> LlmResponse
                      -> LlmToolCall
```

`LlmRequest` may carry the available `ToolDefinition` values as capability
descriptions. It never carries Tool implementations, a registry, or Agent
access. `LlmProvider` performs one synchronous model call and returns
provider-neutral data. It does not discover or execute Tools, call Execution,
or run an Agent loop.

The current deterministic path is:

```text
AgentRequest
  -> AgentService
      -> AgentExecutionPort
          -> ExecutionService
      -> HostExecutionBackend or SandboxExecutionBackend
```

The controlled Agent loop is a separate, synchronous orchestration boundary:

```text
AgentService
  -> AgentOrchestrator
      -> LlmProvider (one generation)
          -> text -> AgentResult SUCCESS
          -> Tool calls -> AgentToolAccess -> ToolResult
                           -> TOOL message -> next generation
```

`AgentOrchestrator` accepts a provider-neutral `AgentPromptRequest`, exposes
only `ToolDefinition` values to the model, and converts each model
`LlmToolCall` into a separate `ToolInvocation`. Tool execution is always
sequential. Tool failures and unknown tools become TOOL messages so the model
can recover; provider failures, invalid empty responses, and a reached
`maxSteps` boundary fail the Agent run. `maxSteps` counts model generations
and is required to be positive, so the loop cannot be unbounded. The
orchestrator does not depend on Spring AI, a ToolRegistry, an Execution port,
or any runtime implementation.

When a model returns Tool Calls, the next request preserves both the assistant
Tool Call message and the matching TOOL result message. This keeps the
provider-neutral conversation complete for OpenAI-compatible adapters while
leaving actual Tool execution under easyCode's `AgentOrchestrator`.

Each model generation is recorded as one immutable `AgentStepTrace` inside an
immutable `AgentRunTrace`. A step records safe metadata such as model,
message/tool counts, response-content presence, duration, outcome, and ordered
Tool observations. Tool observations retain name, call ID, success/failure,
failure category, and input/output presence and lengths; they do not retain
credentials or provider raw responses. `AgentExecution` keeps the final
`AgentResult` and trace separate. The trace is in-memory structured data, not
logging, persistence, an Event Store, or distributed telemetry.

The current JVM implementation is an adapter behind the port:

```text
JvmAgentExecutionAdapter
  -> JvmWorkerRuntime
      -> ExecutionRequest
          -> ExecutionService
```

## Responsibilities

### Agent

`AgentService` keeps the existing deterministic Execution facade and also
accepts the controlled LLM path through `AgentOrchestrator`. The two paths are
separate: the Execution facade depends on `AgentExecutionPort`, while the LLM
facade depends on `LlmProvider` and `AgentToolAccess`. Neither path exposes
runtime, sandbox, registry, or framework implementations to the other.

### Execution

Executes one command synchronously and returns a common result containing status,
exit code, output, duration, and termination reason. It does not know about
Agent planning or Canvas concepts.

### Runtime

Describes what is being run. The current runtime adapter is
`JvmWorkerRuntime`, which turns a small JVM Worker operation into an
`ExecutionRequest`. The older `SandboxRunner` remains a runtime-specific
convenience API and is not the Agent API.

### Execution Environment

Selects where the execution occurs: `HOST` or `SANDBOX`. It is separate from
the runtime and from sandbox policy.

### Sandbox

Provides process isolation and Windows-specific resource or security controls
behind `SandboxService` and `SandboxBackend`. Sandbox is execution
infrastructure, not the easyCode product itself. Agent code does not call
Windows managers, JNA, Win32, or `SandboxRunner` directly.

### Tool

A Tool is a callable capability exposed to an Agent. The current Tool contract
contains `ToolDefinition`, `ToolInvocation`, `ToolResult`, `Tool`, and
`ToolRegistry`. The registry performs only in-process registration, name
resolution, listing, and invocation. Tool failures are converted to stable
ToolResult failure categories instead of exposing implementation exceptions.

Tool is not Execution: a future Tool may use Java APIs directly, or may choose
to call an execution capability when that is appropriate. Tool is also not a
Runtime or Sandbox.

The current `RegistryAgentToolAccess` is only an adapter around the existing
registry. Tool failures remain `ToolResult` values; they are not automatically
converted into an `AgentRun` failure. Deciding what a Tool failure means for a
larger Agent operation belongs to a future orchestration layer.

### LLM Provider

The LLM boundary consists of `LlmProvider`, `LlmRequest`, `LlmMessage`,
`LlmResponse`, and `LlmToolCall`, with `LlmException` as the provider-neutral
failure boundary. `LlmRequest.tools` reuses the existing `ToolDefinition`
model, preserving its name, description, and input schema without duplicating
the Tool capability contract. No AI framework type is part of this core
contract.

`LlmToolCall` means that the model requested an action. It is not a
`ToolInvocation`, which means that the application is actually invoking a
Tool. The conversion and any decision to execute belong to a future Agent
orchestration layer.

### Spring AI Adapter

`SpringAiLlmProvider` is an infrastructure adapter that implements the core
`LlmProvider` contract by calling Spring AI's low-level `ChatModel` API. The
adapter converts core messages, model selection, Tool definitions, and Spring
AI responses at the boundary. Spring AI 2.0.1 is used through the minimal
`spring-ai-model` module and is not part of `llm.api`, `llm.model`, or
`llm.exception`.

Tool definitions are exposed through descriptor-only Spring AI callbacks
because the low-level API carries definitions in `ToolCallingChatOptions`. The
callbacks reject `call(...)`; `SpringAiLlmProvider` never executes an easyCode
Tool, owns the registry, or starts a Tool Calling loop. `ChatClient`,
`ToolCallingAdvisor`, and Spring AI orchestration features are intentionally
outside this boundary.

The first concrete model configuration is OpenAI-compatible:

```text
OpenAiCompatibleModelConfig
    -> OpenAiChatModelFactory
        -> OpenAiChatModel
            -> SpringAiLlmProvider
```

The configuration requires a valid HTTP(S) `baseUrl`, a non-blank API key, and
a model name. It supports custom OpenAI-compatible endpoints and redacts the
API key from its `toString` output. Factory creation only constructs the
client/model; it does not send a network request. Real credentials and model
calls are outside this slice.

### Real Provider Verification

`RealLlmSmokeTest` is an explicit external integration test, not part of the
normal offline regression. It only runs when
`EASYCODE_REAL_LLM_TEST=true`, `EASYCODE_LLM_API_KEY`, and
`EASYCODE_LLM_MODEL` are present. `EASYCODE_LLM_BASE_URL` is optional and
defaults to the configured OpenAI endpoint. Without these settings the smoke
test is skipped and must be reported as `NOT_RUN`, never as a pass.

The smoke request is a short text-only prompt and succeeds only when the
provider returns a non-empty `LlmResponse`. It does not expose credentials in
test output, does not execute Tools, and does not start an Agent or Tool
Calling loop. Run it explicitly with `mvn -Dtest=RealLlmSmokeTest test` only
when external access is intentionally enabled.

`RealAgentToolCallingSmokeTest` is a separate explicit external test. When the
same environment variables are enabled, it verifies a real model Tool Call,
the test-only `add` Tool, the returned `5` Tool result, the second model
request, and the final AgentResult. The `add` Tool exists only under
`src/test/java`; it does not expose shell, file, network, database, JVM, or
sandbox capabilities. It is never part of the default offline regression.

## Current Agent Core scope

Implemented:

- `AgentRequest`
- `AgentRun` and a small terminal lifecycle
- `AgentResult` and Agent-level failure classification
- `AgentExecutionPort` as the runtime-independent Agent boundary
- `JvmAgentExecutionAdapter` as the current JVM implementation
- the independent Tool contract and in-process ToolRegistry
- `AgentToolAccess` for Agent-side Tool discovery and invocation
- the framework-independent LLM Provider contract and model Tool Call data
- `SpringAiLlmProvider` as the Spring AI `ChatModel` adapter
- `OpenAiCompatibleModelConfig` and `OpenAiChatModelFactory` for concrete model creation
- `RealLlmSmokeTest` as an explicitly enabled external verification path
- `AgentOrchestrator` as a bounded synchronous LLM/Tool loop
- `RealAgentToolCallingSmokeTest` as an explicitly enabled end-to-end Tool
  Calling verification path
- real Host and Windows Sandbox integration tests

Not implemented in this slice:

- production external LLM calls or prompt handling
- Planner, Workflow, Canvas, Node, Port, or Trigger
- Runtime registry or additional runtimes
- persistence, scheduling, asynchronous execution, or realtime logs
- debugger variables, threads, or stack inspection
- other model-provider configurations, MCP, AI framework, or Tool plugin integrations
- Planner, ReAct, Memory, persistence, cancellation, and asynchronous Agent
  execution
