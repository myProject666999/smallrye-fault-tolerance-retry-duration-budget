package io.smallrye.faulttolerance.core.retry;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link BackOff} wrapper that counts how many times {@link #getInMillis(Throwable)} was called.
 */
public final class CountingBackOff implements BackOff {
    private final BackOff delegate;
    private final AtomicLong count = new AtomicLong();

    public CountingBackOff(BackOff delegate) {
        this.delegate = delegate;
    }

    @Override
    public long getInMillis(Throwable cause) {
        count.incrementAndGet();
        return delegate.getInMillis(cause);
    }

    public long count() {
        return count.get();
    }
}
