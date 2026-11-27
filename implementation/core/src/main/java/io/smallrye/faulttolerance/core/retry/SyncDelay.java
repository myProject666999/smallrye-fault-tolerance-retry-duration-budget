package io.smallrye.faulttolerance.core.retry;

import java.util.function.Supplier;

/**
 * Performs a delay synchronously. That is, blocks the calling thread for the duration of the delay.
 */
public interface SyncDelay {
    /**
     * Sentinel value returned by {@link #getDelayInMillis(Throwable)} when the delay
     * cannot be known before the blocking wait starts. In that case, the caller must
     * fall back to the legacy {@link #sleep(Throwable)} behavior, i.e. wait first
     * and check the elapsed time afterwards.
     */
    long UNKNOWN_DELAY = -1L;

    void sleep(Throwable cause) throws InterruptedException;

    /**
     * Computes the delay that a subsequent {@link #sleep(Throwable)} call would block for,
     * without actually blocking. The delay value is computed at most once per retry decision:
     * if this method returns a known, non-negative value, that exact same value is passed
     * to {@link #sleep(Throwable, long)} and {@link #sleep(Throwable)} is never called.
     * <p>
     * Implementations that cannot know the delay up front (for example a delay driven
     * by a synchronization barrier) return {@link #UNKNOWN_DELAY}; the legacy
     * {@link #sleep(Throwable)} path is then used.
     *
     * @param cause exception causing the retry attempt before which the delay occurs
     * @return the delay in milliseconds, {@code 0} for no delay, or {@link #UNKNOWN_DELAY}
     *         if the delay cannot be determined before waiting
     */
    default long getDelayInMillis(Throwable cause) {
        return UNKNOWN_DELAY;
    }

    /**
     * Blocks the calling thread for given {@code delayInMillis}. This method is only called
     * when a previous call to {@link #getDelayInMillis(Throwable)} returned a known value;
     * that value is passed here unchanged, so the delay is never computed twice.
     *
     * @param cause exception causing the retry attempt before which the delay occurs
     * @param delayInMillis the delay value previously returned by {@link #getDelayInMillis(Throwable)},
     *        never negative
     */
    default void sleep(Throwable cause, long delayInMillis) throws InterruptedException {
        sleep(cause);
    }

    Supplier<SyncDelay> NONE = () -> new SyncDelay() {
        @Override
        public void sleep(Throwable cause) {
        }

        @Override
        public long getDelayInMillis(Throwable cause) {
            return 0;
        }

        @Override
        public void sleep(Throwable cause, long delayInMillis) {
        }
    };
}
