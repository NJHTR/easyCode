# easyCode

easyCode is a local Agent system and an executable instruction language under
construction. The current repository contains the Windows Sandbox
Infrastructure, Execution Foundation / Runtime Boundary, a minimal easyCode
instruction runtime, and Agent Core slices. Agent Core supports both
deterministic local tests and a bounded LLM/Tool Calling path through the local
application. Canvas and workflow layers are future visual representations of
the language, not a separate execution model.

The layer responsibilities and dependency direction are documented in
[`docs/architecture.md`](docs/architecture.md).

## easyCode language runtime

The language runtime executes an immutable `EasyCodeProgram` made of
`EasyCodeInstruction` values. The first built-in instructions are variable
assignment and console output with `${variable}` interpolation:

```text
EasyCodeProgram
    -> EasyCodeInstruction[]
        -> InProcessEasyCodeRuntime
            -> console output + variable snapshot + failure instruction
```

This is intentionally separate from Java, C++, or Python. Those are possible
implementation tools for future integrations, but easyCode's own program is
the instruction sequence and its runtime owns the execution semantics. A
future canvas can edit the same program model, and an Agent can produce or
modify validated instructions without calling UI classes directly.

## Canvas Graph Foundation

The product's visual foundation is a graph of canvases, nodes, ports, and
connections. The current slice is deliberately UI-independent:

```text
CanvasDefinition
    -> CanvasNode[]
        -> CanvasPort[]
    -> CanvasConnection[]
        -> CanvasGraphValidator
```

`CanvasDefinition` and its nested records use UUID identities, so a display
name can change without changing the identity used by connections. The graph
validator checks unique identities, port ownership, output-to-input direction,
and duplicate connection endpoints. The model does not yet render a canvas,
execute a graph, persist documents, or implement triggers, scheduling, or
workflow semantics. Those concerns must be layered above this stable graph
contract.

The graph can also be converted into a `CanvasExecutionPlan`. This is a
deterministic, immutable topological order of node IDs produced by
`CanvasExecutionPlanner`; it validates the graph and rejects cycles, but it is
not a node executor and does not invoke the Execution or Sandbox layers.

`CanvasExecutionEngine` is the first small synchronous consumer of that plan.
It dispatches a registered executor by `CanvasNode.nodeType`, passes in-memory
values across connected ports, and returns a terminal `CanvasExecutionResult`.
The result includes immutable per-node traces with the observed inputs,
outputs, duration, and failure message when applicable.
Standard containers in trace values are recursively copied. Applications with
custom mutable value types can provide a `CanvasExecutionValueSnapshotter` to
`CanvasService`; this changes trace representations only, not values passed
between nodes or returned in `outputValues`.
Executors may read only declared input ports; attempting to read an output or
unknown port fails the current node with an explicit execution error.
Built-in `print` and `passthrough` nodes also require their `in` port to be
connected; a connected `null` remains a valid value and is not treated as a
missing connection.
When an output port has a declared downstream connection, its executor must
publish a value for that port; otherwise the source node fails explicitly
instead of silently delivering a missing input. Unconnected output ports may
remain unpublished.
It deliberately does not start processes, schedule work, or choose Host versus
Sandbox; those concerns remain behind the separate Execution boundary.

`CanvasExecutionRequest` is the minimal launch contract for one synchronous
run. Each request carries an `executionId` so repeated runs of the same canvas
can be correlated; convenience factories generate one, while advanced callers
may provide one explicitly. Its optional `entryNodeIds` selects one or more
root nodes and executes only their reachable subgraph. An empty list preserves
the original behavior: all implicit root nodes are planned. Its optional `initialInputs` map supplies
values to unconnected input ports, including an explicit `null`; connected
ports remain owned by upstream node outputs. Unknown, output, connected, or
out-of-scope ports are rejected. An explicit entry must exist and cannot have
an incoming connection. A future Run command or trigger may construct this
request, but the request itself is not a trigger or scheduler.

`CanvasExecutionPreflight` prepares the same request without invoking any node
executor. It returns the same `executionId`, planned node IDs, and structured
diagnostics for an invalid graph, invalid input scope, or missing executor.
`CanvasService` and `CanvasApplication` expose this as a read-only readiness
check; a ready result does not start execution and a failed result does not
change the canvas. The eventual `CanvasExecutionResult` carries that same ID.

The returned `CanvasExecutionResult.outputValues` is an immutable snapshot of
values published by successfully completed nodes, keyed by output-port ID. It
allows a caller to consume a canvas result without scanning node traces. Values
published by a node that fails are not included in this aggregate snapshot.

A canvas may optionally expose public `inputBindings` and `outputBindings`.
Each stable external name maps to an internal port UUID, so callers can provide
`left=10` rather than knowing a generated port identity. Public inputs must map
to unconnected input ports, and declared public outputs must be published when
their node runs. `CanvasExecutionRequest.withNamedInputs` resolves public input
names, while `CanvasExecutionResult.namedOutputValues` returns the declared
results by name. The original UUID-based request and result APIs remain
available for lower-level callers.

Execution snapshots also provide query helpers. `CanvasExecutionResult` can
look up an output by port ID, a public output by name, or a node trace by node
ID. `CanvasNodeExecutionTrace` can query captured inputs and outputs by port.
`CanvasValueLookup` keeps a `present` flag separate from its value so an
explicitly published `null` remains different from a missing value.

`CanvasBuiltinExecutors` provides a deliberately small in-memory baseline:
`constant`, `passthrough`, `print`, and `add`. `add` consumes explicit `left`
and `right` numeric input ports and produces a decimal sum, demonstrating
multi-input node semantics without making the Canvas layer depend on a foreign
language, JVM process, or operating-system sandbox. Applications can register
their own node types through the same `CanvasNodeExecutor` contract.

`CanvasService` is the simple application facade for this synchronous Canvas
execution contract. Its default constructor uses the built-in executors, and
callers can also inject a custom executor set or a prepared
`CanvasExecutionRequest`. It does not add scheduling, triggers, persistence, or
another execution algorithm; it keeps those concerns outside the Canvas slice.

Callers that need lightweight run observation can pass a
`CanvasExecutionObserver` to the request-based `execute` method. The observer
receives `STARTED`, debugger pause/resume transitions, per-node lifecycle events,
and one terminal event synchronously on the execution thread. Events use
the request's `executionId` and a zero-based, monotonically increasing
`sequence` within that execution; they are not retained by the application.
Node events also carry immutable snapshots of the node's input values, produced
output values, and console lines available at that lifecycle point. Every event
also records its `occurredAt` timestamp for inspection and correlation; event
ordering continues to use `sequence`.
Standard maps, collections, arrays, and dates inside value snapshots
are copied into read-only structures. Custom value objects are retained by
reference by default because the runtime cannot safely clone arbitrary user
types. A `CanvasExecutionEventCollector` can be constructed with a
`CanvasExecutionValueSnapshotter` to project such values into immutable debug
representations.
When a node executor throws, its `NODE_FAILED` event includes the exception
type, message, and formatted stack trace in `failureDetails`; graph validation
failures still expose their existing message without a fabricated stack trace.
The same details are available from the failed node's `CanvasNodeExecutionTrace`,
so callers that do not subscribe to lifecycle events can still inspect them.
Callers that need to inspect the complete event sequence after the synchronous
run can pass a `CanvasExecutionEventCollector` as the observer and read its
immutable `events()` snapshot, or query it by node or lifecycle event type.
The collector validates that the recorded events belong to one execution, have
contiguous sequence numbers, and end with one terminal event.
It also exposes structured live queries for the active node, paused node,
successfully completed node IDs, published output values, console lines, and
terminal status while the synchronous call is running or after it returns.
The collector also provides execution identity, null-safe lookup by output port,
and per-node console queries.
This is a small in-process observation hook, not a realtime log stream, event bus,
debugger, scheduler, or persistence layer. Invalid requests rejected during
preflight emit no lifecycle events.
Synchronous runs also accept a `CanvasExecutionCancellationToken`. Cancellation
is cooperative: the engine checks between nodes and node executors can check the
token during long work. It does not forcibly interrupt or kill a running node.

For node-boundary debugging, `CanvasExecutionDebugger` supports per-node
breakpoints, pause, resume, and one-node stepping. It does not stop a node in the
middle of its execution. The execution call stays synchronous; callers that
need to control it concurrently run that call on a thread they own. Cancellation
also releases an execution waiting at a debugger pause. A pause event includes
the selected node's input snapshot before that node starts.

`CanvasNodeCatalog` exposes the available node type descriptions and can
create a node with generated node and port identities. The built-in catalog is
available through `CanvasBuiltinNodes.catalog()`. It describes node shape only;
execution still resolves the matching `CanvasNodeExecutor` through the existing
`CanvasExecutionEngine`.

`CanvasNodeRegistry` is the unified registration boundary when a node type must
be both discoverable and executable. One `CanvasNodeRegistration` supplies its
descriptor and executor together; the registry derives the catalog and executor
map and rejects duplicate types. `CanvasBuiltinLibrary.registry()` is the
canonical built-in registration, while the older built-in entry points remain
compatibility views.

`CanvasBuilder` assembles those nodes into an immutable validated canvas. It
connects nodes and exposes public inputs or outputs by port name, so callers do
not need to copy generated port UUIDs by hand. `build()` delegates graph-wide
checks to the existing validator; the builder does not execute the canvas or
define when a run should start.

`CanvasApplication` is the smallest end-to-end application facade. It uses one
`CanvasNodeRegistry` for both node creation and execution, creates matching
`CanvasBuilder` instances, and delegates runs to `CanvasService`. It adds no new
graph algorithm, lifecycle, scheduling, trigger, persistence, or UI behavior.

The current source entry point is intentionally small and deterministic:

```text
set name = "easyCode"
println "hello ${name}"
print !
```

`EasyCodeSourceParser` converts this line-oriented source into the same
`EasyCodeProgram` used by the runtime. It currently supports comments, string,
integer, boolean, and null literals, plus `set`, `print`, and `println`.
Unsupported syntax is rejected with a source line number; this is a language
front end, not a Java/C++/Python compiler.

For a direct source-to-result call, use `EasyCodeSourceRuntime`; it composes
the parser with the existing in-process runtime and keeps parsing failures
separate from execution failures.

## Start the headless local application

The repository provides a small Windows PowerShell launcher for the current
one-shot `LocalAgentApplication` boundary. Maven also produces an executable
fat JAR for local distribution; this is not an installer or a production
update mechanism.

From the repository root in PowerShell:

```powershell
$env:EASYCODE_LLM_API_KEY = '<your-provider-key>'
$env:EASYCODE_LLM_MODEL = 'gpt-5.6-sol'
$env:EASYCODE_LLM_BASE_URL = 'https://llmapi.xfcxb.com/v1'
$env:EASYCODE_AGENT_MAX_STEPS = '5' # optional
& .\scripts\start-local-agent.ps1 'Reply with a short greeting.'
```

The Maven package is directly executable without a separate classpath:

```powershell
& mvn.cmd -q clean package
& java -jar .\target\easyCode-1.0-SNAPSHOT.jar --help
```

If Maven is not on `PATH`, invoke the Maven bundled with IntelliJ or set
`EASYCODE_MAVEN_CMD` as described below.

The JAR still reads credentials only from the process environment. To verify
the safe missing-configuration path in a PowerShell process, remove the
variables before invoking it:

```powershell
Remove-Item Env:EASYCODE_LLM_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:EASYCODE_LLM_MODEL -ErrorAction SilentlyContinue
& java -jar .\target\easyCode-1.0-SNAPSHOT.jar 'offline launch check'
```

That command exits non-zero before creating a provider request. The JAR keeps
the same one-shot CLI contract as the PowerShell launcher; it is not a service
or interactive runtime.

To print a safe summary of the completed run, including run/request IDs,
status, failure classification, step outcomes, durations, and Tool observation
counts, add `-Trace`:

```powershell
& .\scripts\start-local-agent.ps1 -Trace 'Reply with a short greeting.'
```

Trace mode intentionally omits the prompt, API key, Tool inputs/outputs, and
raw provider responses. It includes safe Tool failure-reason counts such as
`TIMEOUT:1`; the default command continues to print only the Agent message on
success.

To inspect the command without configuring an LLM or making a network request:

```powershell
& .\scripts\start-local-agent.ps1 --help
```

The direct Java entry point also accepts `-h` and `--help`. A normal prompt
still requires `EASYCODE_LLM_API_KEY` and `EASYCODE_LLM_MODEL`.

The API key is read from the process environment by `LocalAgentApplication`;
the launcher does not put it in command-line arguments, print it, or store it
in the repository. If Maven is not on `PATH`, set `EASYCODE_MAVEN_CMD` to an
absolute `mvn.cmd` path before launching. The default `mvn clean test` remains
offline; the launcher only makes a network request when the configured
provider is actually called. A successful Agent response is written to stdout
and exits with code `0`; configuration or Agent failures are summarized on
stderr and exit with a non-zero code.

Execution backends do not forward inherited Agent/provider configuration
variables such as `EASYCODE_LLM_API_KEY` or `OPENAI_API_KEY` to child processes.
Callers can still provide an explicit value through the execution request
environment when a task is intentionally authorized to use it.

The sandbox layer is intentionally independent from Java project/JDK
management. It accepts a generic Windows process command and owns its
lifecycle. Java runtimes are callers of this API; they do not change the
sandbox contract.

## Execution Foundation / Runtime Boundary

The execution layer is intentionally thin:

```text
ExecutionRequest
    -> ExecutionBackend
        -> HostExecutionBackend
        -> SandboxExecutionBackend -> SandboxService -> SandboxBackend
```

`ExecutionRequest` describes what to run and carries the target `HOST` or
`SANDBOX` environment; the caller supplies the matching backend to
`ExecutionService`.
`ExecutionResult` provides the same status, exit code, stdout, stderr, duration,
and termination reason for both environments. Output exceeding the configured
limit is reported as `OUTPUT_LIMIT` rather than a successful process exit.
`JvmWorkerRuntime` adapts the existing short-lived JVM worker to this contract
for compatibility with the original demo. For real user-provided Java code,
`JdkRuntime` builds requests for the installed JDK's `javac` and `java`
executables from caller-supplied source files, output directory, main class,
classpath, program arguments, environment, and working directory. The caller
executes those requests through `ExecutionService`, so the same Java request
can target the Host or Sandbox backend. `SandboxRunner` remains the older
runtime-specific convenience API and is not required by the execution layer.

The current `ProcessSandboxManager` is the first lifecycle backend:

- creates a process with a unique `SandboxHandle`;
- captures stdout and stderr with bounded output;
- reports status, PID, timestamps, exit code, and output through `query`;
- enforces a wall-clock timeout;
- destroys the process and its descendants and cleans temporary directories.

The process backend currently enforces the timeout and output limit only.
`maxMemoryBytes`, `maxProcessCount`, and the paths/network policy are carried
forward for the Windows-native backend and are not security controls yet.

## Windows Job Object backend

`WindowsJobObjectSandboxManager` uses JNA and Win32 APIs. It starts the child
in a suspended state, applies Job Object limits, assigns the child to the Job,
and only then resumes it. The Job is configured to terminate all attached
processes when it is closed. It currently enforces the timeout, process-count,
and per-process memory limits, and captures output through temporary files.

Run `com.easycode.sandbox.demo.WindowsJobObjectDemo` on Windows after Maven has
resolved the JNA dependencies. This backend is the resource/lifecycle layer;
it does not restrict file or network access by itself.

## AppContainer backend

`WindowsAppContainerSandboxManager` creates a temporary AppContainer Profile
and passes its security capabilities through the extended process attributes.
The process is still placed in the Job Object before it is resumed. The
profile is deleted when the sandbox finishes. The backend grants the
AppContainer SID access to the generated working directory and to the paths
listed in `readOnlyPaths` or `writablePaths`, then restores the original ACLs.
Only the requested object and its immediate parent are modified; it does not
rewrite ACLs on the whole user profile or project tree.
Network access is denied by default; requesting `networkEnabled` is rejected
until an explicit network capability policy is implemented.

Run `com.easycode.sandbox.demo.WindowsAppContainerDemo` to verify the profile,
token, and ACL path.

Run the lifecycle example from IntelliJ with
`com.easycode.sandbox.demo.SandboxLifecycleDemo`.

This is the current Windows security backend. `AppContainer` supplies the
restricted process identity and the backend combines it with Job Object
limits. File access is allow-listed through Windows ACLs; network access is
denied unless a future capability policy explicitly enables it. Registry and
device access still follow the operating system's AppContainer rules and are
not exposed as separate `SandboxPolicy` fields yet.

## Current Java worker demo

This project contains a small desktop-oriented sandbox baseline:

- the main JVM starts a fresh Worker JVM for every task;
- input and output use stdin/stdout, so no host objects are shared;
- the worker gets heap and stack settings, a CPU-count hint, timeout, and output-size limits;
- a temporary working directory is used for `user.dir`, `user.home`, and Java temp files;
- the worker is killed on timeout and the temporary directory is cleaned recursively.

The worker above is a legacy compatibility demo and intentionally supports
only its small operation protocol. It is not the general Java execution path.
Use `JdkRuntime` with `JavaCompilationSpec` and `JavaLaunchSpec` when the
application needs to compile and run real Java source with the installed JDK;
IDEA is not required. IDEA's `-javaagent` option is only a debugger/launcher
integration and is not needed for ordinary compilation or execution.

The project root remains `C:\Users\NJHTR\IdeaProjects\easyCode`. The
`com.easycode.sandbox` part is only the Java package namespace; it does not
rename or move the project itself. Public sandbox APIs stay under that package,
while platform backends and JVM runtime code use dedicated subpackages.

Current source layout:

```text
com.easycode.sandbox.api             public facade and backend interface
com.easycode.sandbox.model           entities, configuration, and status enum
com.easycode.sandbox.exception       sandbox-specific exceptions
com.easycode.sandbox.process         ordinary process backend
com.easycode.sandbox.windows         Windows Job Object/AppContainer backend
com.easycode.runtime.jvm             JVM worker runtime, separate from OS isolation
com.easycode.sandbox.demo             executable smoke-test demos under src/test
```

The root `com.easycode.sandbox` package is intentionally empty. New public
interfaces go in `api`, domain records/enums go in `model`, and exceptions go
in `exception`; do not put implementation classes back in the root package.

Run from IntelliJ by starting `com.easycode.sandbox.demo.SandboxDemo`.

The original Java worker demo can be compiled without external dependencies:

```text
javac -d target/classes src/main/java/com/easycode/runtime/jvm/SandboxRunner.java src/main/java/com/easycode/runtime/jvm/SandboxTask.java src/main/java/com/easycode/runtime/jvm/SandboxWorker.java src/test/java/com/easycode/sandbox/demo/SandboxDemo.java
java -cp target/classes com.easycode.sandbox.demo.SandboxDemo
```

The Windows Job Object backend requires the JNA dependencies declared in
`pom.xml`; run it from IntelliJ or Maven so those dependencies are on the
classpath. The dedicated smoke test is
`com.easycode.sandbox.demo.WindowsJobObjectDemo`.

Expected output is similar to:

```text
sum      = 6.75
uppercase= CANVAS NODE
timeout  = sandbox task exceeded 800 ms
```

## Important security boundary

The plain process backend is only a process-isolation and resource-limit demo;
it is **not a complete OS sandbox for malicious code**. Use the Windows
AppContainer backend when file and network boundaries are required. A host
crash during ACL updates still needs operational cleanup (for example a
startup reconciliation pass), and `ActiveProcessorCount` is not a hard CPU
quota.

For production, keep the worker API allow-listed and add an OS-specific layer:

- Windows: AppContainer and Job Objects (AppContainer supplies the restricted identity);
- macOS: signed App Sandbox helper;
- Linux: bubblewrap/user namespaces and seccomp.

Do not load arbitrary third-party JARs into the main JVM and do not use
`SecurityManager` as the design basis on JDK 24 or later.
