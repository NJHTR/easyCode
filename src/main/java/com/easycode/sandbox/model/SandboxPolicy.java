package com.easycode.sandbox.model;

import java.nio.file.Path;
import java.util.List;

/** Security policy requested by a sandbox. Enforcement is backend-specific. */
public record SandboxPolicy(
        boolean networkEnabled,
        List<Path> readOnlyPaths,
        List<Path> writablePaths) {

    public SandboxPolicy {
        readOnlyPaths = readOnlyPaths == null ? List.of() : List.copyOf(readOnlyPaths);
        writablePaths = writablePaths == null ? List.of() : List.copyOf(writablePaths);
    }

    public static SandboxPolicy defaults() {
        return new SandboxPolicy(false, List.of(), List.of());
    }
}
