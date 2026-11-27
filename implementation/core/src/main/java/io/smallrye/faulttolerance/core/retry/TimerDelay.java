package io.smallrye.faulttolerance.core.retry;

import static io.smallrye.faulttolerance.core.util.Preconditions.checkNotNull;

import java.util.concurrent.Executor;

import io.smallrye.faulttolerance.core.timer.Timer;

/**
 * Asynchronous delay based on {@link Timer}. Its default executor is the timer's {@link Executor}.
 */
public class TimerDelay implements AsyncDelay {
    private final BackOff backOff;
    private final Timer timer;

    public TimerDelay(BackOff backOff, Timer timer) {
        this.backOff = checkNotNull(backOff, "Back-off must be set");
        this.timer = checkNotNull(timer, "Timer must be set");
    }

    @Override
    public void after(Throwable cause, Runnable task, Executor executor) {
        long delay = backOff.getInMillis(cause);
        sleepFor(delay, task, executor);
    }

    @Override
    public long getDelayInMillis(Throwable cause) {
        // a negative delay (e.g. initial exponential delay with negative jitter) is treated as 0
        return Math.max(0, backOff.getInMillis(cause));
    }

    @Override
    public void after(Throwable cause, Runnable task, Executor executor, long delayInMillis) {
        sleepFor(delayInMillis, task, executor);
    }

    private void sleepFor(long delayInMillis, Runnable task, Executor executor) {
        if (delayInMillis > 0) {
            timer.schedule(delayInMillis, task, executor);
        } else {
            task.run();
        }
    }
}
