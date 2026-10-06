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

When one Agent composition must support both environments, an
`EnvironmentExecutionBackend` routes each `ExecutionRequest` by its explicit
`ExecutionEnvironment` before delegating to the Host or Sandbox backend. A
single-environment `ExecutionService` remains valid for focused callers, but
it should not be used as a substitute for environment routing. The concrete
Host and Sandbox backends also reject requests for the wrong environment
instead of silently weakening the requested boundary.

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
and is required to be positive. Each generation is also limited to 32 Tool
Calls; exceeding that hard safety ceiling fails the run before any of those
Tools execute. Tool call IDs must also be unique within one model response; a
duplicate is treated as an invalid provider response before any Tool executes.
The orchestration boundary also verifies that each `AgentToolAccess` result
preserves the requested Tool call ID; a null or mismatched result becomes a
structured internal Tool failure before it is added to model feedback or the
run trace.
Together these bounds keep the synchronous loop bounded. The orchestrator also
applies the `AgentPromptRequest.timeout()` wall-clock budget
to each provider generation. A timed-out generation is interrupted best-effort,
records a `TIMEOUT` step, and terminates the run as `TIMED_OUT` with failure
reason `TIMEOUT`; no Tool calls are made for that response. The default timeout
is 30 seconds and callers can provide a shorter explicit duration. This remains
a synchronous boundary, not an asynchronous cancellation API, and never uses
`Thread.stop`; an interrupted caller is mapped to a terminal `CANCELLED` run.
The orchestrator does not depend on Spring AI, a ToolRegistry,
an Execution port, or any runtime implementation.

The same request timeout also bounds each synchronous Tool invocation. A Tool
that exceeds the limit is interrupted best-effort and normalized to a
`ToolFailureReason.TIMEOUT` result, which is fed back to the model like other
Tool failures. This prevents a blocking in-process Tool from hanging the Agent
forever; it is not a hard isolation boundary for untrusted code, which still
belongs behind Execution and Sandbox.

Tool feedback sent to a subsequent model generation is bounded to 32,768
characters. Oversized output is truncated with a marker before it becomes
conversation context, while the original Tool result and trace length metadata
remain unchanged. This keeps provider requests and in-memory conversation
growth bounded without changing Tool behavior.

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
`AgentResult` and trace separate, while its construction enforces matching run
and request identities, terminal status, and failure reason. The trace is
in-memory structured data, not logging, persistence, an Event Store, or
distributed telemetry.

Completed in-memory traces can be read through the narrow `AgentTraceQuery`
contract. `InMemoryAgentTraceQuery` indexes existing `AgentRunTrace` values by
their UUID `runId` and exposes only `findByRunId(...)`. A missing run returns
`Optional.empty()`. Returned traces are immutable sorted copies whose Steps are
ordered by ascending `stepNumber`; the query does not execute Agents, call
Tools, or change the original trace. Persistence, REST, and UI are not part of
this boundary.

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
exit code, output, duration, and termination reason. Output-limit termination is
represented as `ExecutionTerminationReason.OUTPUT_LIMIT`, independent of the
underlying process exit code. It does not know about Agent planning or Canvas
concepts.
`ExecutionService` verifies that every backend result preserves the request
`executionId`; a null or mismatched result is normalized to a failed
`INTERNAL_ERROR` result with the original request identity.

Host and sandbox process launchers remove inherited Agent/provider configuration
variables, including API-key variables, before applying explicit request
environment overrides. This prevents a child task from receiving the parent
Agent credential implicitly; an explicitly supplied override remains deliberate.

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
The registry also verifies that a Tool result preserves the invocation call ID
before returning it to the Agent boundary.

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
defaults to the configured OpenAI endpoint. If the explicit smoke-test flag is
disabled, the gate is `NOT_RUN`; if the flag is enabled but provider
credentials are missing, the gate is `PENDING_RETRY`, never a pass.

The smoke request is a short text-only prompt and succeeds only when the
provider returns a non-empty `LlmResponse`. It does not expose credentials in
test output, does not execute Tools, and does not start an Agent or Tool
Calling loop. Run it explicitly with `mvn -Dtest=RealLlmSmokeTest test` only
when external access is intentionally enabled.

`RealAgentToolCallingSmokeTest` is a separate explicit external test. When the
same environment variables are enabled, it verifies a real model Tool Call,
the test-only `add` Tool, the returned `5` Tool result, the second model
request, and the final AgentResult through `LocalAgentApplication` and its
session boundary. The `add` Tool exists only under
`src/test/java`; it does not expose shell, file, network, database, JVM, or
sandbox capabilities. It is never part of the default offline regression.

`RealAgentRunTraceSmokeTest` separately verifies the same real Tool Calling
scenario through `LocalAgentApplication` and `LocalAgentSession.runWithTrace(...)`.
It checks the final
`AgentExecution`, run/request identity, one Step per model generation, ordered
Tool observations, duration fields, successful outcome, and that the trace
contains no credentials or provider-specific objects. It is also explicitly
enabled and never runs in the default offline regression.

The local composition boundary is `LocalAgentComposition`. It owns the small
application wiring needed by a local caller: an application-provided
`LlmProvider`, `ToolRegistry`, and positive `maxSteps` are combined into the
existing `RegistryAgentToolAccess`, `AgentOrchestrator`, and `AgentService`.
Callers use `run(...)` or `runWithTrace(...)` without constructing those
components repeatedly. The boundary does not select providers, register Tool
implementations, expose framework types, or add persistence, UI, workflow,
memory, or a provider registry.

`LocalAgentSession` is the minimal local continuation boundary. A composition
can open a volatile, synchronous session with `openSession()`. The session has
one UUID and correlates each completed `AgentPromptRequest` with its
`AgentResult` and `AgentRunTrace`; callers can list the in-memory executions or
find one by `runId`. The session also exposes an `AgentTraceQuery` view backed
by those completed runs, so a caller can look up a trace without rebuilding a
separate index. It does not persist data, automatically append prior
messages, or provide Agent memory: every call still supplies its complete
provider-neutral prompt. There is no scheduler or asynchronous lifecycle.

The headless local application boundary is `LocalAgentApplication`. It provides
two explicit construction paths: callers may inject an `LlmProvider` for local
or offline wiring tests, or may pass an `OpenAiCompatibleModelConfig` to build
the concrete `OpenAiChatModelFactory` and `SpringAiLlmProvider` adapter. The
command-line `main` path reads `EASYCODE_LLM_API_KEY`,
`EASYCODE_LLM_MODEL`, optional `EASYCODE_LLM_BASE_URL`, and optional
`EASYCODE_AGENT_MAX_STEPS`, then accepts exactly one prompt argument. It creates
an empty application-owned `ToolRegistry`, opens a `LocalAgentSession`, runs
one complete provider-neutral prompt synchronously, and prints only the final
result message. It does not print or persist credentials and is not a UI,
workflow, memory, persistence, or asynchronous runtime.

For a local Windows launch, Maven's standard `package` goal creates an
executable fat JAR whose manifest points to
`com.easycode.agent.application.LocalAgentApplication`. The JAR preserves
Java service-loader resources and can be started with
`java -jar target/easyCode-1.0-SNAPSHOT.jar --help`; provider configuration is
supplied through `EASYCODE_LLM_API_KEY`, `EASYCODE_LLM_MODEL`, optional
`EASYCODE_LLM_BASE_URL`, and optional `EASYCODE_AGENT_MAX_STEPS`. The
`scripts/start-local-agent.ps1` launcher remains available and uses Maven's
generated runtime classpath for a repeatable Windows development launch. Both
paths keep API keys out of command-line arguments and repository files; neither
is an installer or a long-running service.

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
- per-generation timeout handling with terminal trace/result semantics
- `RealAgentToolCallingSmokeTest` as an explicitly enabled end-to-end Tool
  Calling verification path
- `LocalAgentApplication` as the minimal headless local entry boundary for
  explicit OpenAI-compatible configuration and one synchronous prompt
- real Host and Windows Sandbox integration tests

Not implemented in this slice:

- interactive prompt sessions, provider registry, and production configuration
  lifecycle beyond the one-shot headless entry point
- Planner, Workflow, Canvas, Node, Port, or Trigger
- Runtime registry or additional runtimes
- persistence, scheduling, asynchronous execution, or realtime logs
- debugger variables, threads, or stack inspection
- other model-provider configurations, MCP, AI framework, or Tool plugin integrations
- Planner, ReAct, Memory, persistence, an explicit cancellation API, and asynchronous Agent
  execution (other than the bounded provider-generation timeout)
