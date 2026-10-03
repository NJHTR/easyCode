package com.easycode.sandbox.api;

import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxSpec;

/** Lifecycle API shared by the process and Windows-native backends. */
public interface SandboxBackend extends AutoCloseable {
    SandboxHandle create(SandboxSpec spec) throws SandboxException;

    SandboxInfo query(SandboxHandle handle) throws SandboxException;

    void destroy(SandboxHandle handle) throws SandboxException;

    @Override
    void close();
}
