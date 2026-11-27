package io.smallrye.faulttolerance.core.retry;

import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;

/**
 * A {@link SyncDelay} that does not really sleep; instead, it advances the shared
 * {@link TestStopwatch} by the delay value. The delay is computed exactly once
 * through {@link #getDelayInMillis(Throwable)} and the same value is used for waiting.
 */
public final class StopwatchSyncDelay implements SyncDelay {
    private final BackOff backOff;
    private final TestStopwatch stopwatch;

    public StopwatchSyncDelay(BackOff backOff, TestStopwatch stopwatch) {
        this.backOff = backOff;
        this.stopwatch = stopwatch;
    }

    @Override
    public void sleep(Throwable cause) throws InterruptedException {
        sleep(cause, Math.max(0, backOff.getInMillis(cause)));
    }

    @Override
    public long getDelayInMillis(Throwable cause) {
        return Math.max(0, backOff.getInMillis(cause));
    }

    @Override
    public void sleep(Throwable cause, long delayInMillis) throws InterruptedException {
        stopwatch.setCurrentValue(stopwatch.getCurrentValue() + delayInMillis);
    }
}
