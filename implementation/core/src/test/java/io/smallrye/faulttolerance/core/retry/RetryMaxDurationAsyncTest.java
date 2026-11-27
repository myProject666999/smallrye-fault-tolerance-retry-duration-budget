package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.core.FaultToleranceContext;
import io.smallrye.faulttolerance.core.Future;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.timer.TimerTask;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;

/**
 * Asynchronous counterpart of {@link RetryMaxDurationTest}. Uses a real {@link TimerDelay}
 * backed by a {@link FakeTimer}. Scheduled tasks do not run by themselves; the test drives
 * the loop with {@link FakeTimer#executeSynchronously(TimerTask)}, so an early end leaves
 * no scheduled timer task behind.
 */
public class RetryMaxDurationAsyncTest {
    private TestStopwatch stopwatch;
    private FakeTimer timer;

    @BeforeEach
    public void setUp() {
        stopwatch = new TestStopwatch();
        timer = new FakeTimer(stopwatch);
    }

    private <V> Retry<V> retry(TimedFailingInvocation<V> invocation, long maxRetries, long maxDuration,
            BackOff backOff) {
        TimerDelay asyncDelay = new TimerDelay(backOff, timer);
        // the sync delay is never used on an async context, but it must still be present
        return new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                maxRetries, maxDuration, SyncDelay.NONE, () -> asyncDelay, stopwatch, null);
    }

    private static <V> FaultToleranceContext<V> newAsyncContext() {
        return async(null);
    }

    /** Runs every scheduled timer task in order, advancing fake time, until none remain. */
    private void drainScheduledTasks() {
        while (timer.hasScheduledTasks()) {
            timer.executeSynchronously(timer.nextScheduledTask());
        }
    }

    @Test
    public void nextWaitWouldExceedMaxDuration_endsImmediatelyWithoutScheduledTask() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        Future<Void> future = retry.apply(newAsyncContext());
        // the first wait (2000 < 2900 remaining) is scheduled; after it runs, the second wait
        // would overshoot and must never be scheduled
        drainScheduledTasks();
        assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);

        // ends at 2200 ms, 2 calls, and no timer task is left for the skipped wait
        assertThat(stopwatch.getCurrentValue()).isEqualTo(2200);
        assertThat(invocation.numberOfInvocations()).isEqualTo(2);
        assertThat(timer.countScheduledTasks()).isZero();
        assertThat(timer.hasScheduledTasks()).isFalse();
    }

    @Test
    public void firstWaitWouldExceedMaxDuration_noRetryAndNoScheduledTask() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        AtomicInteger retried = new AtomicInteger();
        FaultToleranceContext<Void> ctx = newAsyncContext();
        ctx.registerEventHandler(RetryEvents.Retried.class, ignored -> retried.incrementAndGet());

        Future<Void> future = retry.apply(ctx);
        // the very first wait would overshoot, so nothing is ever scheduled; draining is a no-op
        drainScheduledTasks();
        assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);

        assertThat(stopwatch.getCurrentValue()).isEqualTo(100);
        assertThat(invocation.numberOfInvocations()).isEqualTo(1);
        assertThat(retried).hasValue(0);
        assertThat(timer.countScheduledTasks()).isZero();
    }

    @Test
    public void retryThatFitsBudget_schedulesAndRunsTimerTasks() {
        // delay 500, max duration 3000, zero-duration calls, max retries 2:
        // every wait fits the budget, so the retry count (not the duration) ends the loop
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(500, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 2, 3000, backOff);

        Future<Void> future = retry.apply(newAsyncContext());
        drainScheduledTasks();

        assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
        assertThat(stopwatch.getCurrentValue()).isEqualTo(1000);
        assertThat(timer.countScheduledTasks()).isZero();
    }

    @Test
    public void delayEqualToRemainingDuration_isConsideredOvershooting() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        BackOff backOff = new ConstantBackOff(1000, Jitter.ZERO);
        Retry<Void> retry = retry(invocation, 10, 3000, backOff);

        Future<Void> future = retry.apply(newAsyncContext());
        // two waits of 1000 are scheduled and run; the third would land exactly on 3000 and is skipped
        drainScheduledTasks();

        assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);
        assertThat(stopwatch.getCurrentValue()).isEqualTo(2000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(3);
        assertThat(timer.countScheduledTasks()).isZero();
    }

    @Test
    public void exponentialBackoff_asyncMatchesSyncTiming() {
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 0);
        CountingBackOff backOff = new CountingBackOff(new ExponentialBackOff(1000, 2, Jitter.ZERO, 0));
        Retry<Void> retry = retry(invocation, 10, 10000, backOff);

        Future<Void> future = retry.apply(newAsyncContext());
        drainScheduledTasks();

        assertThatThrownBy(future::awaitBlocking).isExactlyInstanceOf(RuntimeException.class);
        assertThat(stopwatch.getCurrentValue()).isEqualTo(7000);
        assertThat(invocation.numberOfInvocations()).isEqualTo(4);
        assertThat(backOff.count()).isEqualTo(4);
        assertThat(timer.countScheduledTasks()).isZero();
    }
}
