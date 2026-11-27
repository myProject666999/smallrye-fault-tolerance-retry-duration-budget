package io.smallrye.faulttolerance.core.metrics;

import static io.smallrye.faulttolerance.core.FaultToleranceContextUtil.sync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import io.smallrye.faulttolerance.core.circuit.breaker.CircuitBreakerEvents;
import io.smallrye.faulttolerance.core.retry.AsyncDelay;
import io.smallrye.faulttolerance.core.retry.BackOff;
import io.smallrye.faulttolerance.core.retry.ConstantBackOff;
import io.smallrye.faulttolerance.core.retry.Jitter;
import io.smallrye.faulttolerance.core.retry.Retry;
import io.smallrye.faulttolerance.core.retry.StopwatchSyncDelay;
import io.smallrye.faulttolerance.core.retry.TimedFailingInvocation;
import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.util.ExceptionDecision;
import io.smallrye.faulttolerance.core.util.ResultDecision;

/**
 * Verifies that the {@code retried} flag reported with {@code retryMaxDurationReached}
 * reflects whether any {@code Retried} event actually fired, including the new
 * "end immediately when the next wait would exceed the max duration" behavior.
 */
public class RetryMaxDurationMetricsTest {
    @Test
    public void maxDurationReachedWithoutAnyRetry_reportsRetriedFalse() {
        TestStopwatch stopwatch = new TestStopwatch();
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(5000, Jitter.ZERO);
        Retry<Void> retry = new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                10, 3000, () -> new StopwatchSyncDelay(backOff, stopwatch), AsyncDelay.NONE,
                stopwatch, null);

        RecordingRecorder metrics = new RecordingRecorder();
        MetricsCollector<Void> collector = new MetricsCollector<>(retry, metrics, new RetryOnlyOperation());

        assertThatThrownBy(() -> collector.apply(sync(null)).awaitBlocking())
                .isExactlyInstanceOf(RuntimeException.class);

        assertThat(metrics.retryAttempted).hasValue(0);
        assertThat(metrics.maxDurationReached).hasValue(1);
        assertThat(metrics.lastMaxDurationRetried).isFalse();
    }

    @Test
    public void maxDurationReachedAfterOneRetry_reportsRetriedTrue() {
        TestStopwatch stopwatch = new TestStopwatch();
        TimedFailingInvocation<Void> invocation = new TimedFailingInvocation<>(stopwatch, 100);
        BackOff backOff = new ConstantBackOff(2000, Jitter.ZERO);
        Retry<Void> retry = new Retry<>(invocation, "test invocation",
                ResultDecision.ALWAYS_EXPECTED, ExceptionDecision.ALWAYS_FAILURE,
                10, 3000, () -> new StopwatchSyncDelay(backOff, stopwatch), AsyncDelay.NONE,
                stopwatch, null);

        RecordingRecorder metrics = new RecordingRecorder();
        MetricsCollector<Void> collector = new MetricsCollector<>(retry, metrics, new RetryOnlyOperation());

        assertThatThrownBy(() -> collector.apply(sync(null)).awaitBlocking())
                .isExactlyInstanceOf(RuntimeException.class);

        assertThat(metrics.retryAttempted).hasValue(1);
        assertThat(metrics.maxDurationReached).hasValue(1);
        assertThat(metrics.lastMaxDurationRetried).isTrue();
    }

    private static final class RetryOnlyOperation implements MeteredOperation {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean mayBeAsynchronous() {
            return false;
        }

        @Override
        public boolean hasBulkhead() {
            return false;
        }

        @Override
        public int bulkheadLimit() {
            return 0;
        }

        @Override
        public int bulkheadQueueSize() {
            return 0;
        }

        @Override
        public boolean hasCircuitBreaker() {
            return false;
        }

        @Override
        public long circuitBreakerDelay() {
            return 0;
        }

        @Override
        public int circuitBreakerRequestVolumeThreshold() {
            return 0;
        }

        @Override
        public double circuitBreakerFailureRatio() {
            return 0;
        }

        @Override
        public int circuitBreakerSuccessThreshold() {
            return 0;
        }

        @Override
        public boolean hasFallback() {
            return false;
        }

        @Override
        public boolean hasRateLimit() {
            return false;
        }

        @Override
        public int rateLimitLimit() {
            return 0;
        }

        @Override
        public long rateLimitWindow() {
            return 0;
        }

        @Override
        public long rateLimitMinSpacing() {
            return 0;
        }

        @Override
        public boolean hasRetry() {
            return true;
        }

        @Override
        public int retryMaxRetries() {
            return 10;
        }

        @Override
        public long retryMaxDuration() {
            return 3000;
        }

        @Override
        public boolean hasTimeout() {
            return false;
        }

        @Override
        public long timeoutDuration() {
            return 0;
        }

        @Override
        public String name() {
            return "retry-metrics-test";
        }

        @Override
        public Object cacheKey() {
            return name();
        }
    }

    private static final class RecordingRecorder implements MetricsRecorder {
        final AtomicInteger retryAttempted = new AtomicInteger();
        final AtomicInteger maxDurationReached = new AtomicInteger();
        volatile boolean lastMaxDurationRetried;

        @Override
        public void executionFinished(boolean succeeded, boolean fallbackDefined, boolean fallbackApplied,
                long duration) {
        }

        @Override
        public void retryAttempted() {
            retryAttempted.incrementAndGet();
        }

        @Override
        public void retryValueReturned(boolean retried) {
        }

        @Override
        public void retryExceptionNotRetryable(boolean retried) {
        }

        @Override
        public void retryMaxRetriesReached(boolean retried) {
        }

        @Override
        public void retryMaxDurationReached(boolean retried) {
            maxDurationReached.incrementAndGet();
            lastMaxDurationRetried = retried;
        }

        @Override
        public void timeoutFinished(boolean timedOut, long time) {
        }

        @Override
        public void circuitBreakerFinished(CircuitBreakerEvents.Result result) {
        }

        @Override
        public void circuitBreakerMovedToOpen() {
        }

        @Override
        public void registerCircuitBreakerIsClosed(BooleanSupplier supplier) {
        }

        @Override
        public void registerCircuitBreakerIsOpen(BooleanSupplier supplier) {
        }

        @Override
        public void registerCircuitBreakerIsHalfOpen(BooleanSupplier supplier) {
        }

        @Override
        public void registerCircuitBreakerTimeSpentInClosed(LongSupplier supplier) {
        }

        @Override
        public void registerCircuitBreakerTimeSpentInOpen(LongSupplier supplier) {
        }

        @Override
        public void registerCircuitBreakerTimeSpentInHalfOpen(LongSupplier supplier) {
        }

        @Override
        public void bulkheadDecisionMade(boolean accepted) {
        }

        @Override
        public void registerBulkheadExecutionsRunning(LongSupplier supplier) {
        }

        @Override
        public void registerBulkheadExecutionsWaiting(LongSupplier supplier) {
        }

        @Override
        public void updateBulkheadRunningDuration(long time) {
        }

        @Override
        public void updateBulkheadWaitingDuration(long time) {
        }

        @Override
        public void rateLimitDecisionMade(boolean permitted) {
        }
    }
}
