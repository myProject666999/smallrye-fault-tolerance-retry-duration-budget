package io.smallrye.faulttolerance.core.retry;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Performs a delay asynchronously. That is, schedules a task to be executed after the delay,
 * potentially on another thread. If no delay is needed, the task may be executed directly
 * on the calling thread.
 */
public interface AsyncDelay {
    /**
     * Sentinel value returned by {@link #getDelayInMillis(Throwable)} when the delay
     * cannot be known before the asynchronous wait is scheduled. In that case, the caller
     * must fall back to the legacy {@link #after(Throwable, Runnable, Executor)} behavior,
     * i.e. schedule the wait first and check the elapsed time afterwards.
     */
    long UNKNOWN_DELAY = -1L;

    /**
     * Runs the {@code task} after delay on given {@link Executor}.
     * If given {@code executor} is {@code null}, the task is executed on an implementation-defined thread.
     */
    void after(Throwable cause, Runnable task, Executor executor);

    /**
     * Computes the delay that a subsequent {@link #after(Throwable, Runnable, Executor, long)}
     * call would wait for, without actually scheduling anything. The delay value is computed
     * at most once per retry decision: if this method returns a known, non-negative value,
     * that exact same value is passed to
     * {@link #after(Throwable, Runnable, Executor, long)} and
     * {@link #after(Throwable, Runnable, Executor)} is never called.
     * <p>
     * Implementations that cannot know the delay up front return {@link #UNKNOWN_DELAY};
     * the legacy {@link #after(Throwable, Runnable, Executor)} path is then used.
     *
     * @param cause exception causing the retry attempt before which the delay occurs
     * @return the delay in milliseconds, {@code 0} for no delay, or {@link #UNKNOWN_DELAY}
     *         if the delay cannot be determined before waiting
     */
    default long getDelayInMillis(Throwable cause) {
        return UNKNOWN_DELAY;
    }

    /**
     * Runs the {@code task} after given {@code delayInMillis} on given {@link Executor}.
     * This method is only called when a previous call to {@link #getDelayInMillis(Throwable)}
     * returned a known value; that value is passed here unchanged, so the delay is never
     * computed twice. If given {@code executor} is {@code null}, the task is executed
     * on an implementation-defined thread.
     *
     * @param cause exception causing the retry attempt before which the delay occurs
     * @param task the task to run after the delay
     * @param executor executor on which the task is run, or {@code null} for an implementation-defined thread
     * @param delayInMillis the delay value previously returned by {@link #getDelayInMillis(Throwable)},
     *        never negative
     */
    default void after(Throwable cause, Runnable task, Executor executor, long delayInMillis) {
        after(cause, task, executor);
    }

    Supplier<AsyncDelay> NONE = () -> new AsyncDelay() {
        @Override
        public void after(Throwable cause, Runnable task, Executor executor) {
            task.run();
        }

        @Override
        public long getDelayInMillis(Throwable cause) {
            return 0;
        }

        @Override
        public void after(Throwable cause, Runnable task, Executor executor, long delayInMillis) {
            task.run();
        }
    };
}
