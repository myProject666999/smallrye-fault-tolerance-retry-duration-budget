package io.smallrye.faulttolerance.core.retry;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import io.smallrye.faulttolerance.core.stopwatch.TestStopwatch;
import io.smallrye.faulttolerance.core.timer.Timer;
import io.smallrye.faulttolerance.core.timer.TimerTask;

/**
 * A fake {@link Timer} for asynchronous retry tests with fake time. A scheduled task is stored
 * in a queue and only executed via {@link #executeSynchronously(TimerTask)}, which first advances
 * the shared {@link TestStopwatch} by the scheduled delay. Mirrors the queue semantics
 * of the project's {@code TestTimer}.
 */
public final class FakeTimer implements Timer {
    private final TestStopwatch stopwatch;
    private final Queue<Task> tasks = new ConcurrentLinkedQueue<>();

    public FakeTimer(TestStopwatch stopwatch) {
        this.stopwatch = stopwatch;
    }

    @Override
    public int getId() {
        return 0;
    }

    @Override
    public TimerTask schedule(long delayInMillis, Runnable runnable) {
        return schedule(delayInMillis, runnable, null);
    }

    @Override
    public TimerTask schedule(long delayInMillis, Runnable runnable, Executor executor) {
        Task task = new Task(delayInMillis, runnable);
        tasks.add(task);
        return task;
    }

    @Override
    public int countScheduledTasks() {
        return tasks.size();
    }

    public boolean hasScheduledTasks() {
        return !tasks.isEmpty();
    }

    public TimerTask nextScheduledTask() {
        return tasks.peek();
    }

    public void executeSynchronously(TimerTask task) {
        if (tasks.remove(task)) {
            Task typed = (Task) task;
            stopwatch.setCurrentValue(stopwatch.getCurrentValue() + typed.delayInMillis);
            typed.runnable.run();
        }
    }

    @Override
    public void shutdown() {
    }

    private static final class Task implements TimerTask {
        private final long delayInMillis;
        private final Runnable runnable;

        private Task(long delayInMillis, Runnable runnable) {
            this.delayInMillis = delayInMillis;
            this.runnable = runnable;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean cancel() {
            return false;
        }
    }
}
