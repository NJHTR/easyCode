package com.easycode.sandbox.api;

import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxSpec;

import java.util.Objects;

/** Application facade; platform-specific details stay behind SandboxBackend. */
public final class SandboxService implements AutoCloseable {
    private final SandboxBackend backend;

    public SandboxService(SandboxBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public SandboxHandle create(SandboxSpec spec) throws SandboxException {
        return backend.create(spec);
    }

    public SandboxInfo query(SandboxHandle handle) throws SandboxException {
        return backend.query(handle);
    }

    public void destroy(SandboxHandle handle) throws SandboxException {
        backend.destroy(handle);
    }

    @Override
    public void close() {
        backend.close();
    }
}
