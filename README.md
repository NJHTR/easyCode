# easyCode

easyCode is a local Agent system under construction. The current repository
contains the Windows Sandbox Infrastructure, Execution Foundation / Runtime
Boundary, and Agent Core slices. Agent Core is currently deterministic; Agent
intelligence, Canvas, and workflow layers are future slices.

The layer responsibilities and dependency direction are documented in
[`docs/architecture.md`](docs/architecture.md).

The sandbox layer is intentionally independent from Java project/JDK
management. It accepts a generic Windows process command and owns its
lifecycle. A Java worker will be just one future caller of this API.

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
and termination reason for both environments. `JvmWorkerRuntime` adapts the
existing short-lived JVM worker to this contract. `SandboxRunner` remains the
older runtime-specific convenience API and is not required by the execution
layer.

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
