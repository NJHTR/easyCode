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
  -> LlmProvider
      -> LlmRequest / LlmResponse
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

The current JVM implementation is an adapter behind the port:

```text
JvmAgentExecutionAdapter
  -> JvmWorkerRuntime
      -> ExecutionRequest
          -> ExecutionService
```

## Responsibilities

### Agent

Accepts one user-level request, creates one run, calls Execution, and converts
the result into an AgentResult. The current Agent is intentionally a thin
deterministic orchestrator and depends only on `AgentExecutionPort`. It does
not perform LLM reasoning, planning, workflow management, memory, tool calling,
or multi-agent coordination.

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
- real Host and Windows Sandbox integration tests

Not implemented in this slice:

- LLM integration or prompt handling
- Planner, Workflow, Canvas, Node, Port, or Trigger
- Runtime registry or additional runtimes
- persistence, scheduling, asynchronous execution, or realtime logs
- debugger variables, threads, or stack inspection
- concrete LLM provider, MCP, AI framework, or Tool plugin integrations
- Agent Loop, automatic Tool selection, Planner, and ReAct execution
