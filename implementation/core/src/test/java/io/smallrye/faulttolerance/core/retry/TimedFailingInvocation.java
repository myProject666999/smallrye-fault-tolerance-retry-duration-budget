package io.smallrye.faulttolerance.core.retry;

import java.util.concurrent.atomic.AtomicInteger;

import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.Future;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;

/**
 * A guarded invocation that always fails and advances the shared {@link TestStopwatch}
 * by a fixed duration on every call, simulating the guarded method's execution time
 * with fake time.
 */
public final class TimedFailingInvocation<V> implements FaultToleranceStrategy<V> {
    private final TestStopwatch stopwatch;
    private final long durationInMillis;
    private final AtomicInteger invocationCounter = new AtomicInteger(0);

    public TimedFailingInvocation(TestStopwatch stopwatch, long durationInMillis) {
        this.stopwatch = stopwatch;
        this.durationInMillis = durationInMillis;
    }

    @Override
    public Future<V> apply(FaultToleranceContext<V> ctx) {
        invocationCounter.incrementAndGet();
        stopwatch.setCurrentValue(stopwatch.getCurrentValue() + durationInMillis);
        return Future.ofError(new RuntimeException("forced failure"));
    }

    public int numberOfInvocations() {
        return invocationCounter.get();
    }
}
