package com.easycode.canvas.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooperative cancellation signal for one canvas execution. */
public final class CanvasExecutionCancellationToken {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    /** Requests cancellation. Returns true only for the first request. */
    public boolean cancel() {
        return cancellationRequested.compareAndSet(false, true);
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /** Stops a cooperative node executor by throwing when cancellation was requested. */
    public void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("canvas execution cancelled");
        }
    }
}
