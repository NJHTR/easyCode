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
