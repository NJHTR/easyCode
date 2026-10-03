# Windows Sandbox Integration Evidence

Date: 2026-10-04

## Command

```text
mvn clean test
```

The local Maven executable was resolved from the existing Maven 3.9.x wrapper
distribution cache because `mvn` is not on `PATH`. The repository itself does
not contain a Maven wrapper yet.

## Results

| Test class | Tests | Failures | Errors | Result |
|---|---:|---:|---:|---|
| `SandboxLifecycleIntegrationTest` | 5 | 0 | 0 | PASS |
| `WindowsSandboxIntegrationTest` | 2 | 0 | 0 | PASS |
| **Total** | **7** | **0** | **0** | **PASS** |

## Verified behaviors

- Real process lifecycle and terminal status.
- Real stdout and stderr capture.
- Wall-clock timeout.
- Explicit workspace write.
- Abnormal exit status propagation.
- Parent and child process destruction in the ordinary process backend.
- Windows Job Object process startup and timeout enforcement.
- AppContainer workspace write access.
- AppContainer unauthorized read denial.
- AppContainer unauthorized write denial.
- AppContainer explicit read-only access.

## Not proven by this slice

- Hard CPU quota.
- Thread-count limiting.
- Network isolation by a real network attempt.
- Memory-limit enforcement under a controlled memory-pressure process.
- Recovery of ACL state after a host crash.
- A standalone `CreateRestrictedToken` implementation.

The unproven items remain future work and are intentionally outside this test
slice. No mock Process, Job Object, AppContainer, ACL, or Win32 implementation
is used by the integration tests.
