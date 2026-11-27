package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.core.FailureContext;
import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;

/**
 * Verifies that a retry wait which would reach or exceed the configured max duration
 * is not performed at all: the loop ends immediately, reusing the last failure.
 * All timing uses fake time: guarded invocations and delay implementations just
 * advance a {@link TestStopwatch}.
 */
public class RetryMaxDurationTest {
    private TestStopwatch stopwatch;

    @BeforeEach
    public void setUp() {
        stopwatch = new TestStopwatch();
    }

    private <V> Retry<V> retry(TimedFailingInvocation<V> invocation, long maxRetries, long maxDuration,
            BackOff backOff) {
        StopwatchSyncDelay delay = new StopwatchSyncDelay(backOff, stopwatch);
        return new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                maxRetries, maxDuration, () -> delay, AsyncDelay.NONE, stopwatch, null);
    }

    private static <V> void runExpectingFailure(Retry<V> retry, FaultToleranceContext<V> ctx,
            Class<? extends Throwable> expected) {
        assertThatThrownBy(() -> retry.apply(ctx).awaitBlocking()).isExactlyInstanceOf(expected);
    }

    private static <V> FaultToleranceContext<V> newContext() {
        // TimedFailingInvocation never calls ctx.call(), so a null callable is safe
        return sync(null);
    }

    @Test
    public void nextWaitWouldExceedMaxDuration_endsImmediately() {
        // delay 2000, max duration 3000, each call takes 100, all fail
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // ends at 2200 ms: call at 0-100, wait 2000, second call at 2100-2200, then stop
        assertThat(stopwatch.getCurrentValue()).isEqualTo(2200);
        assertThat(invocation.numberOfInvocations()).isEqualTo(2);
    }

    @Test
    public void firstWaitWouldExceedMaxDuration_noRetryAtAll() {
        // delay 5000, max duration 3000, first call takes 100
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        assertThat(stopwatch.getCurrentValue()).isEqualTo(100);
        assertThat(invocation.numberOfInvocations()).isEqualTo(1);
    }

    @Test
    public void lastFailureInstanceIsRethrown() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        FaultToleranceContext<Void> ctx = newContext();
        assertThatThrownBy(() -> retry.apply(ctx).awaitBlocking())
                .isExactlyInstanceOf(RuntimeException.class)
                .hasMessage("forced failure");
    }

    @Test
    public void exponentialBackoff_delayComputedOncePerDecision() {
        // initial delay 1000, factor 2, no jitter, max duration 10000, each call takes 0
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        CountingBackOff backOff = new CountingBackOff(new ExponentialBackOff(1000, 2, Jitter.ZERO, 0));
        Retry<Void> retry = retry(invocation, 10, 10000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // delays 1000 + 2000 + 4000 = 7000; the 8000 wait would exceed the remaining 3000, so no 4th wait
        assertThat(stopwatch.getCurrentValue()).isEqualTo(7000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(4);
        // getInMillis is called exactly once per retry decision, no pre-computation
        assertThat(backOff.count()).isEqualTo(4);
    }

    @Test
    public void delayEqualToRemainingDuration_isConsideredOvershooting() {
        // delay 1000, max duration 3000, each call takes 0
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(1000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // 0 -> wait 1000 -> 1000 -> wait 1000 -> 2000; next wait would land exactly on 3000, so stop
        assertThat(stopwatch.getCurrentValue()).isEqualTo(2000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
    }

    @Test
    public void maxRetriesReachedFirst_reportsMaxRetries() {
        // zero delay and a generous duration: retry count is exhausted before the duration
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(0, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 3, 3000, backOff);

        AtomicInteger maxRetriesReached = new AtomicInteger();
        AtomicInteger maxDurationReached = new AtomicInteger();
        FaultToleranceContext<Void> ctx = newContext();
        ctx.registerEventHandler(RetryEvents.Finished.class, event -> {
            if (event.result == RetryEvents.Result.MAX_RETRIES_REACHED) {
                maxRetriesReached.incrementAndGet();
            } else if (event.result == RetryEvents.Result.MAX_DURATION_REACHED) {
                maxDurationReached.incrementAndGet();
            }
        });

        runExpectingFailure(retry, ctx, RuntimeException.class);

        assertThat(invocation.numberOfInvocations()).isEqualTo(4);
        assertThat(maxRetriesReached).hasValue(1);
        assertThat(maxDurationReached).hasValue(0);
    }

    @Test
    public void zeroMaxDurationMeansInfinite_durationLimitNeverTriggers() {
        // max duration 0 means infinite; retry count still bounds the loop
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 3, 0, backOff);

        AtomicInteger maxRetriesReached = new AtomicInteger();
        AtomicInteger maxDurationReached = new AtomicInteger();
        FaultToleranceContext<Void> ctx = newContext();
        ctx.registerEventHandler(RetryEvents.Finished.class, event -> {
            if (event.result == RetryEvents.Result.MAX_RETRIES_REACHED) {
                maxRetriesReached.incrementAndGet();
            } else if (event.result == RetryEvents.Result.MAX_DURATION_REACHED) {
                maxDurationReached.incrementAndGet();
            }
        });

        runExpectingFailure(retry, ctx, RuntimeException.class);

        assertThat(invocation.numberOfInvocations()).isEqualTo(4);
        assertThat(stopwatch.getCurrentValue()).isEqualTo(6000);
        assertThat(maxRetriesReached).hasValue(1);
        assertThat(maxDurationReached).hasValue(0);
    }

    @Test
    public void retriedEventFiresOnlyForPerformedRetries() {
        // 2000 / 3000 case: exactly one retry is performed, then the loop ends early
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        AtomicInteger retried = new AtomicInteger();
        AtomicInteger maxDurationReached = new AtomicInteger();
        FaultToleranceContext<Void> ctx = newContext();
        ctx.registerEventHandler(RetryEvents.Retried.class, ignored -> retried.incrementAndGet());
        ctx.registerEventHandler(RetryEvents.Finished.class,
                event -> maxDurationReached.set(event.result == RetryEvents.Result.MAX_DURATION_REACHED ? 1 : 0));

        runExpectingFailure(retry, ctx, RuntimeException.class);

        assertThat(retried).hasValue(1);
        assertThat(maxDurationReached).hasValue(1);
    }

    @Test
    public void retriedEventDoesNotFireWhenEndingImmediately() {
        // 5000 / 3000 case: no retry at all
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        AtomicInteger retried = new AtomicInteger();
        AtomicInteger maxDurationReached = new AtomicInteger();
        FaultToleranceContext<Void> ctx = newContext();
        ctx.registerEventHandler(RetryEvents.Retried.class, ignored -> retried.incrementAndGet());
        ctx.registerEventHandler(RetryEvents.Finished.class,
                event -> maxDurationReached.set(event.result == RetryEvents.Result.MAX_DURATION_REACHED ? 1 : 0));

        runExpectingFailure(retry, ctx, RuntimeException.class);

        assertThat(retried).hasValue(0);
        assertThat(maxDurationReached).hasValue(1);
    }

    @Test
    public void beforeRetryIsNotCalledOnEarlyEnd() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        StopwatchSyncDelay delay = new StopwatchSyncDelay(backOff, stopwatch);
        AtomicInteger beforeRetry = new AtomicInteger();
        Retry<Void> retry = new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                10, 3000, () -> delay, AsyncDelay.NONE, stopwatch,
                (FailureContext ctx) -> beforeRetry.incrementAndGet());

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        assertThat(invocation.numberOfInvocations()).isEqualTo(1);
        assertThat(beforeRetry).hasValue(0);
    }

    @Test
    public void computedBackOff_userFunctionCalledOncePerRetryDecision() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        AtomicInteger functionCalls = new AtomicInteger();
        BackOff backOff = new ComputedBackOff(
                cause -> {
                    functionCalls.incrementAndGet();
                    return Duration.ofMillis(1000);
                },
                new ConstantBackOff(100, Jitter.ZERO), Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // 3 calls, hence 3 retry decisions, each invoking the user function exactly once
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
        assertThat(functionCalls).hasValue(3);
    }

    @Test
    public void computedBackOff_throwingFunctionFallsBack_andIsInvokedOncePerDecision() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        AtomicInteger functionCalls = new AtomicInteger();
        // the user function always throws; ComputedBackOff logs one warning and uses the fallback,
        // so the fallback's 100 ms delay drives the loop
        BackOff backOff = new ComputedBackOff(
                cause -> {
                    functionCalls.incrementAndGet();
                    throw new IllegalStateException("boom");
                },
                new ConstantBackOff(100, Jitter.ZERO), Jitter.ZERO);
        StopwatchSyncDelay delay = new StopwatchSyncDelay(backOff, stopwatch);
        Retry<Void> retry = new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                10, 300, () -> delay, AsyncDelay.NONE, stopwatch, null);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // 3 calls and 3 retry decisions; the throwing function (and thus its single warning)
        // is evaluated once per decision, never twice for the same decision
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
        assertThat(functionCalls).hasValue(3);
        // two 100 ms fallback waits: 0 -> 100 -> 200; the next would land exactly on 300, so stop
        assertThat(stopwatch.getCurrentValue()).isEqualTo(200);
    }

    @Test
    public void unknownDelayFallsBackToWaitFirstCheckAfterwards() {
        // a delay that cannot be predicted: legacy path must still work
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff knownBackOff = new ConstantBackOff(2000, Jitter.ZERO);
        SyncDelay opaqueDelay = new SyncDelay() {
            @Override
            public void sleep(Throwable cause) throws InterruptedException {
                // emulate the real wait by advancing the stopwatch by the back-off value
                stopwatch.setCurrentValue(stopwatch.getCurrentValue() + knownBackOff.getInMillis(cause));
            }

            // intentionally does NOT override getDelayInMillis -> UNKNOWN_DELAY
        };
        Retry<Void> retry = new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                10, 3000, () -> opaqueDelay, AsyncDelay.NONE, stopwatch, null);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // legacy behavior: the overshooting wait is performed and the limit is only detected afterwards
        assertThat(invocation.numberOfInvocations()).isEqualTo(2);
        assertThat(stopwatch.getCurrentValue()).isEqualTo(4200);
    }

    @Test
    public void negativeInitialExponentialDelayIsTreatedAsZero() {
        // ExponentialBackOff's first value is not clamped; with a negative jitter it may be < 0
        Jitter negativeJitter = () -> -2000L;
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ExponentialBackOff(1000, 2, negativeJitter, 0);
        Retry<Void> retry = retry(invocation, 2, 10000, backOff);

        runExpectingFailure(retry, newContext(), RuntimeException.class);

        // first delay -1000 treated as 0; max retries still bounds the number of calls
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
    }
}
