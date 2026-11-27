package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.util.Preconditions.checkNotNull;

/**
 * Sync delay based on {@code Thread.sleep}.
 */
public class ThreadSleepDelay implements SyncDelay {
    private final BackOff backOff;

    public ThreadSleepDelay(BackOff backOff) {
        this.backOff = checkNotNull(backOff, "Back-off must be set");
    }

    @Override
    public void sleep(Throwable cause) throws InterruptedException {
        long delay = backOff.getInMillis(cause);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    @Override
    public long getDelayInMillis(Throwable cause) {
        // a negative delay (e.g. initial exponential delay with negative jitter) is treated as 0
        return Math.max(0, backOff.getInMillis(cause));
    }

    @Override
    public void sleep(Throwable cause, long delayInMillis) throws InterruptedException {
        if (delayInMillis > 0) {
            Thread.sleep(delayInMillis);
        }
    }
}
