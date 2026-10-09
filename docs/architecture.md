# easyCode Architecture Boundary

easyCode is being built as a local, general-purpose Agent system. The current
repository contains infrastructure slices, not the complete product.

easyCode also has its own executable instruction language. Its program model is
independent of Java or any other implementation language:

```text
EasyCodeProgram
  -> EasyCodeInstruction[]
      -> EasyCodeRuntime
          -> EasyCodeExecutionResult
```

`InProcessEasyCodeRuntime` is the first small interpreter. It executes trusted
instructions synchronously, exposes console output and variable snapshots, and
identifies the instruction that failed while preserving completed state. The
initial built-ins are `SetVariableInstruction`, `ComputeVariableInstruction`,
`PrintInstruction`, `ConditionalInstruction`, and bounded
`RepeatInstruction`. `EasyCodeExpressions` provides small composable
language expressions for constants, typed variable reads, integer addition,
and equality without coupling the language runtime to Java application code.
These are language instructions, not Canvas widgets. A future Canvas may edit
the same program model, while a future Agent may create or transform programs
through a validated language boundary.

## Canvas Graph Foundation

The visual product model is a graph document, independent of UI technology and
operating-system sandbox details:

```text
CanvasDefinition
  -> CanvasNode[]
      -> CanvasPort[]
  -> CanvasConnection[]
      -> CanvasGraphValidator
```

`CanvasDefinition`, `CanvasNode`, `CanvasPort`, and `CanvasConnection` are
immutable declarations with UUID identities. A node's display name is separate
from its identity. `CanvasGraphValidator` verifies graph-wide identity
uniqueness, that connection endpoints belong to their declared nodes, and that
connections run from an output port to an input port without duplicate
endpoints. An input port has at most one incoming connection, while an output
port may fan out to multiple downstream inputs. This makes value propagation
deterministic and leaves future merge semantics explicit instead of silently
overwriting an input value. This slice intentionally stops at a validated graph model: it does
not render a UI, execute nodes, define triggers, schedule work, persist
canvases, or implement workflow semantics. Future execution layers may consume
this model without making the Canvas package depend on Sandbox or Windows APIs.

`CanvasExecutionPlanner` is the next structural boundary above the graph
model. It validates a `CanvasDefinition` and returns an immutable
`CanvasExecutionPlan` containing a deterministic topological order of node
identities. The planner rejects cycles, but it does not execute nodes, resolve
node handlers, carry port values, or call `ExecutionService`. Those runtime
semantics require a later slice with an explicit node execution contract.

`CanvasExecutionEngine` now provides that minimal contract for synchronous
in-memory nodes. A `CanvasNodeExecutor` is selected by `nodeType`, receives a
`CanvasNodeExecutionContext`, and may publish values by output port. The engine
transfers those values along validated connections and returns a terminal
`CanvasExecutionResult` with immutable per-node traces. The traces are a
post-run observation, not a realtime debugger or persistent log. Trace values
recursively snapshot standard maps, collections, arrays, and dates so later
nodes cannot mutate previously captured container state; custom objects remain
shared references unless a `CanvasExecutionValueSnapshotter` is configured on
`CanvasService` or an observer collector. Trace snapshot projection does not
replace values passed between nodes or returned in `outputValues`. A connected
output must be published by its source executor; if it is absent, the source
node fails explicitly before downstream execution. Outputs without downstream
connections remain optional. Executors may read only declared input ports;
reading an output or unknown port fails the current node explicitly. This is
also why built-in `print` and `passthrough` reject an unconnected `in` port
while preserving a connected `null` as a real value. This is
not a scheduler, process launcher, or
Sandbox adapter; a later runtime adapter may use the result of this layer to
request Host or Sandbox execution.

`CanvasExecutionRequest` adds the smallest explicit launch boundary. A request
contains an `executionId`, a canvas, optionally one or more entry node IDs, and
optionally a map of initial values keyed by input-port ID. Convenience factories
generate the ID; advanced callers can provide one to correlate a preflight and
its eventual result. An empty entry list keeps the
compatibility behavior of executing from all implicit roots. When entries are
supplied, the planner validates that they exist, have no incoming connections,
and that every reachable node has all of its required upstream paths inside the
selected subgraph. Initial values may target only unconnected input ports in
that execution scope, and a map entry can deliberately carry `null`. The
request describes one run; it does not introduce a trigger, scheduler,
persistence, or a special `MainNode` type.

`CanvasExecutionPreflight` is the non-executing preparation boundary for the
same request. It reuses the planner and input-scope checks, reports the same
execution ID and planned node IDs, and returns stable diagnostics for invalid
requests, invalid graphs, or missing executors. `CanvasService` and
`CanvasApplication` expose the check without invoking a node executor; a
successful preflight is readiness evidence, not a run or a debugger session.
The eventual `CanvasExecutionResult` preserves the request's execution ID.

`CanvasExecutionResult.outputValues` is the immutable aggregate of output-port
values published by successfully completed nodes. It preserves explicit `null`
values and provides a direct result boundary for callers that should not parse
node traces. Partial output from a failing node is intentionally excluded.

`CanvasDefinition.inputBindings` and `outputBindings` form the optional public
boundary of a callable canvas. They map stable external names to internal port
UUIDs without changing graph connection identity. A public input can target
only an unconnected input port. A declared public output must be published when
its node participates in the run. Named request values are resolved to the
existing UUID-based execution contract, and named result values are projected
from its immutable output snapshot. This is a call boundary only; it does not
add triggers, scheduling, persistence, or nested-canvas execution.

Post-run query helpers expose output values and node traces without changing
the immutable snapshot model. `CanvasValueLookup` represents both presence and
the stored value, because `null` is a valid explicit Canvas value and cannot be
used to mean that a port or public output was absent.

`CanvasBuiltinExecutors` contains a small deterministic in-memory baseline:
`constant`, `passthrough`, `print`, and `add`. The `add` node consumes explicit
`left` and `right` numeric input ports and emits a decimal sum, proving that
multi-input merge behavior is represented by named ports rather than by
silently overwriting one input. These nodes are not a complete standard
library or a replacement for the separate easyCode language runtime.

`CanvasService` is the application-facing facade over `CanvasExecutionEngine`.
It provides default built-in executors, named-input convenience methods,
selected-entry execution, and prepared-request execution. It deliberately
performs no scheduling or persistence and contains no second graph algorithm;
callers that need lower-level control may continue using the engine directly.

The request-based execution overload also accepts a `CanvasExecutionObserver`.
It reports `STARTED`, debugger pause/resume transitions, node-level lifecycle
events, and one terminal event inline on the synchronous execution thread.
Every event carries the request's execution ID and a zero-based monotonically
increasing sequence number for that execution. Node events also carry immutable
input, output, and console snapshots for that node invocation. The observer is a
callback only, and each event records an `occurredAt` timestamp for inspection
and correlation; `sequence` remains the authoritative event order. The timestamp
is observational metadata, not a scheduling or persistence mechanism:
events are neither queued nor retained, and observer exceptions are allowed to
propagate to the caller. Preflight or planning rejection happens before
`STARTED`, so rejected requests produce no lifecycle events. This deliberately
does not create realtime logs, an event bus, a debugger session, a scheduler,
or persistence.

For callers that need a post-run inspection view, `CanvasExecutionEventCollector`
implements the same observer contract and exposes an immutable in-memory event
snapshot after the synchronous call returns. It is a convenience collector, not
an execution registry or a persistence layer. Its node and event-type queries
preserve execution order and return immutable lists. It validates contiguous
event numbering, node start/outcome pairs, debugger pause/resume transitions,
and that terminal outcomes agree with the last node lifecycle state.
The collector requires one execution ID, contiguous event sequence numbers, and
a terminal success or failure event; it rejects attempts to append events after
completion.
Event value snapshots recursively copy standard maps, collections, arrays, and
dates; arbitrary custom value objects remain shared references
by default because their safe copying semantics are type-specific. Callers may
give `CanvasExecutionEventCollector` a `CanvasExecutionValueSnapshotter` to
project custom runtime values into immutable debug representations without
changing the values passed between nodes.
If a node executor throws, its `NODE_FAILED` event carries immutable failure
details with the exception type, message, and formatted stack trace. Engine
validation failures do not claim to have an exception stack when none exists.
The same thrown-exception details are included in the failed node trace returned
by `CanvasExecutionResult`, independent of whether an observer was supplied.

An execution may receive a `CanvasExecutionCancellationToken`. The engine checks
it before starting each node, and a long-running executor may call
`CanvasNodeExecutionContext.throwIfCancellationRequested()` to stop cooperatively.
Cancellation produces a distinct terminal status and event; partial diagnostics
for a cancelled node are retained in its trace but are not published as completed
canvas outputs. The engine does not forcibly interrupt threads.

`CanvasExecutionDebugger` adds an optional node-boundary control plane for one
execution. Callers may set node breakpoints, pause before the next node, resume,
or step one node at a time. A breakpoint pauses before that node starts; a pause
requested during a node takes effect after the node finishes. The debugger does
not interrupt node code. Execution remains synchronous and the caller owns any
thread used to issue controls concurrently. Cancellation releases a paused
execution. This is basic graph-level stepping, not source-line debugging or a
mutable variable watch session.

`CanvasNodeDescriptor` and `CanvasNodeCatalog` form the small discovery and
creation boundary above the raw graph model. A descriptor defines a node type's
display metadata and port shape; creating from it generates fresh node and port
UUIDs. `CanvasBuiltinNodes.catalog()` describes the built-in types, while the
existing executor map remains responsible for behavior. The catalog therefore
does not become a second execution registry or introduce plugin loading.

`CanvasNodeRegistration` joins one descriptor to one executor, and
`CanvasNodeRegistry` derives its catalog and executor map from those
registrations. `CanvasBuiltinLibrary.registry()` is the canonical built-in
source; `CanvasBuiltinNodes` and `CanvasBuiltinExecutors` delegate to it for
compatibility. The graph model remains independent of executable behavior.

`CanvasBuilder` is the matching assembly helper. It accepts created nodes,
resolves named ports when connecting nodes or declaring public bindings, and
produces an immutable `CanvasDefinition`. Its `build()` method reuses
`CanvasGraphValidator`, so builder convenience does not create a second set of
graph rules. It has no execution, trigger, scheduling, or persistence behavior.

`CanvasApplication` composes the registry, builder creation, and synchronous
`CanvasService` behind one application-facing object. The same registry supplies
the node instances and their executors, preventing composition mistakes while
leaving all graph validation and execution rules in their existing components.
It is a convenience boundary, not a second Canvas engine.

The first source front end is `EasyCodeSourceParser`. It accepts a deliberately
small line-oriented syntax (`set`, `print`, and `println`, with comments and
basic literals) and produces the existing immutable `EasyCodeProgram`. Parsing
errors are reported with source line numbers. More syntax can be added behind
this boundary without changing the runtime contract; this parser does not
compile or execute another programming language.

Each run also returns an immutable, in-memory `EasyCodeInstructionTrace`. It
records the start and terminal outcome of every invocation routed through the
execution context, including selected branch instructions and repeated body
instructions. The trace is ordered by invocation sequence and is intended for
post-run inspection; it is not a realtime log stream, debugger, persistence
format, or Agent trace. `completedInstructionCount` and `failedInstructionId`
retain their existing top-level program semantics.

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

The language runtime is a separate execution concern below future Agent and
Canvas layers. It may request the existing Execution boundary when a language
instruction needs an external process, but it does not directly depend on
Windows APIs or a specific foreign language runtime.

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
`LlmToolCall` into a separate `ToolInvocation`. It snapshots the available Tool
definitions once per generation so the request capability list and trace count
describe the same view. Tool execution is always
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
distributed telemetry. A trace rejects duplicate step numbers and duplicate
Tool observation call IDs so a querying caller cannot observe ambiguous
generation history, and each presence flag must agree with its recorded content
length. A `COMPLETED` step must also contain response text and no Tool calls;
failure, timeout, and cancellation steps may retain partial observations.

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
`INTERNAL_ERROR` result with the original request identity. Because this slice
is synchronous, `CREATED` and `RUNNING` results are also rejected at this
boundary instead of being exposed as completed executions. A closed Host
backend rejects later requests with `START_FAILED` and cleans up a process if
shutdown races with output-reader startup. The service also rejects terminal
results whose status and termination reason disagree, preserving one coherent
outcome for Agent callers; successful results require exit code `0`, while
timeouts and cancellations cannot carry an exit code.

Host and sandbox process launchers remove inherited Agent/provider configuration
variables, including API-key variables, before applying explicit request
environment overrides. This prevents a child task from receiving the parent
Agent credential implicitly; an explicitly supplied override remains deliberate.
Closing a Host execution backend also terminates active process trees before
its output-reader executor shuts down, matching the cleanup guarantee of the
Sandbox execution path.

### Runtime

Describes what is being run. `JvmWorkerRuntime` turns a small JVM Worker
operation into an `ExecutionRequest` for compatibility with the original demo;
the older `SandboxRunner` is another runtime-specific convenience API and is
not the Agent API. These are not the general Java project runner.

For real user-provided Java source, `JdkRuntime` builds ordinary process
requests for the installed JDK's `javac` and `java` executables. The caller
provides `JavaCompilationSpec` (source files, output directory, classpath) and
`JavaLaunchSpec` (main class, classpath, arguments); `ExecutionService` then
executes the resulting requests on `HOST` or `SANDBOX`. No IntelliJ runtime or
IDE-specific agent is required. Compilation failures, application exceptions,
non-zero exits, output limits, and timeouts remain ordinary
`ExecutionResult` outcomes instead of being mapped to a fixed operation list.

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
