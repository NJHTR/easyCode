package com.easycode.sandbox.windows;

import com.easycode.sandbox.api.SandboxBackend;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxSpec;

/** Windows sandbox mode that combines an AppContainer identity with Job Object limits. */
public final class WindowsAppContainerSandboxManager implements SandboxBackend {
    private final WindowsJobObjectSandboxManager delegate;

    public WindowsAppContainerSandboxManager() throws SandboxException {
        delegate = new WindowsJobObjectSandboxManager(true);
    }

    @Override
    public SandboxHandle create(SandboxSpec spec) throws SandboxException {
        return delegate.create(spec);
    }

    @Override
    public SandboxInfo query(SandboxHandle handle) throws SandboxException {
        return delegate.query(handle);
    }

    @Override
    public void destroy(SandboxHandle handle) throws SandboxException {
        delegate.destroy(handle);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
