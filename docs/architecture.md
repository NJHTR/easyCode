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

The current deterministic path is:

```text
AgentRequest
  -> AgentService
      -> JvmWorkerRuntime
          -> ExecutionRequest
              -> ExecutionService
                  -> HostExecutionBackend or SandboxExecutionBackend
```

## Responsibilities

### Agent

Accepts one user-level request, creates one run, calls Execution, and converts
the result into an AgentResult. The current Agent is intentionally a thin
deterministic orchestrator. It does not perform LLM reasoning, planning,
workflow management, memory, tool calling, or multi-agent coordination.

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

## Current Agent Core scope

Implemented:

- `AgentRequest`
- `AgentRun` and a small terminal lifecycle
- `AgentResult` and Agent-level failure classification
- `AgentService` over the existing Execution and JVM Runtime contracts
- real Host and Windows Sandbox integration tests

Not implemented in this slice:

- LLM integration or prompt handling
- Planner, Workflow, Canvas, Node, Port, or Trigger
- Runtime registry or additional runtimes
- persistence, scheduling, asynchronous execution, or realtime logs
- debugger variables, threads, or stack inspection
