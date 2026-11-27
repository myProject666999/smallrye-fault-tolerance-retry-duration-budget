package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.util.TestThread.runOnTestThread;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.FaultToleranceStrategy;
import io.smallrye.faulttolerance.core.Future;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;
import io.smallrye.faulttolerance.core.util.TestThread;

/**
 * Future-returning (pseudo-asynchronous) counterpart of {@link RetryMaxDurationTest}.
 * The guarded method runs on a synchronous context but represents a failed
 * {@code CompletableFuture}, which the enclosing {@code FutureExecution} surfaces as a core
 * error carrying the real exception instance. This test models that by returning
 * {@link Future#ofError(Throwable)}. The call counts and end times must match the purely
 * synchronous and the truly asynchronous paths for the same inputs.
 */
public class RetryMaxDurationFutureTest {
    private TestStopwatch stopwatch;

    @BeforeEach
    public void setUp() {
        stopwatch = new TestStopwatch();
    }

    /** A guarded method that always fails with a real exception, advancing fake time and counting calls. */
    private static final class TimedFailingFutureInvocation
            implements FaultToleranceStrategy<java.util.concurrent.Future<Void>> {
        private final TestStopwatch stopwatch;
        private final long durationInMillis;
        private final AtomicInteger counter = new AtomicInteger();

        private TimedFailingFutureInvocation(TestStopwatch stopwatch, long durationInMillis) {
            this.stopwatch = stopwatch;
            this.durationInMillis = durationInMillis;
        }

        @Override
        public Future<java.util.concurrent.Future<Void>> apply(
                FaultToleranceContext<java.util.concurrent.Future<Void>> ctx) {
            counter.incrementAndGet();
            stopwatch.setCurrentValue(stopwatch.getCurrentValue() + durationInMillis);
            // a failed CompletionStage is propagated as a core error carrying the real exception instance
            return Future.ofError(new RuntimeException("forced failure"));
        }

        int numberOfInvocations() {
            return counter.get();
        }
    }

    private Retry<java.util.concurrent.Future<Void>> retry(TimedFailingFutureInvocation invocation,
            long maxRetries, long maxDuration, BackOff backOff) {
        StopwatchSyncDelay delay = new StopwatchSyncDelay(backOff, stopwatch);
        return new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                maxRetries, maxDuration, () -> delay, AsyncDelay.NONE, stopwatch, null);
    }

    @Test
    public void nextWaitWouldExceedMaxDuration_endsImmediately() {
        TimedFailingFutureInvocation invocation = new TimedFailingFutureInvocation(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<java.util.concurrent.Future<Void>> retry = retry(invocation, 10, 3000, backOff);

        TestThread<java.util.concurrent.Future<Void>> result = runOnTestThread(retry, false);
        assertThatThrownBy(result::await)
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("forced failure");

        assertThat(stopwatch.getCurrentValue()).isEqualTo(2200);
        assertThat(invocation.numberOfInvocations()).isEqualTo(2);
    }

    @Test
    public void firstWaitWouldExceedMaxDuration_noRetryAtAll() {
        TimedFailingFutureInvocation invocation = new TimedFailingFutureInvocation(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        Retry<java.util.concurrent.Future<Void>> retry = retry(invocation, 10, 3000, backOff);

        TestThread<java.util.concurrent.Future<Void>> result = runOnTestThread(retry, false);
        assertThatThrownBy(result::await).isExactlyInstanceOf(RuntimeException.class);

        assertThat(stopwatch.getCurrentValue()).isEqualTo(100);
        assertThat(invocation.numberOfInvocations()).isEqualTo(1);
    }

    @Test
    public void exponentialBackoff_matchesSyncTiming() {
        TimedFailingFutureInvocation invocation = new TimedFailingFutureInvocation(stopwatch, 0);
        CountingBackOff backOff = new CountingBackOff(new ExponentialBackOff(1000, 2, Jitter.ZERO, 0));
        Retry<java.util.concurrent.Future<Void>> retry = retry(invocation, 10, 10000, backOff);

        TestThread<java.util.concurrent.Future<Void>> result = runOnTestThread(retry, false);
        assertThatThrownBy(result::await).isExactlyInstanceOf(RuntimeException.class);

        assertThat(stopwatch.getCurrentValue()).isEqualTo(7000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(4);
        assertThat(backOff.count()).isEqualTo(4);
    }

    @Test
    public void delayEqualToRemainingDuration_isConsideredOvershooting() {
        TimedFailingFutureInvocation invocation = new TimedFailingFutureInvocation(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(1000, Jitter.ZERO);
        Retry<java.util.concurrent.Future<Void>> retry = retry(invocation, 10, 3000, backOff);

        TestThread<java.util.concurrent.Future<Void>> result = runOnTestThread(retry, false);
        assertThatThrownBy(result::await).isExactlyInstanceOf(RuntimeException.class);

        assertThat(stopwatch.getCurrentValue()).isEqualTo(2000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
    }
}
