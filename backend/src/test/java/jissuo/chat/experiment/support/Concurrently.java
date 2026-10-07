package jissuo.chat.experiment.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

public final class Concurrently {
    @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }
    private Concurrently() {}
    public static List<Throwable> run(int threads, Duration duration, ThrowingRunnable body)
            throws InterruptedException {
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(threads)) {
            long deadline = System.nanoTime() + duration.toNanos();
            for (int i = 0; i < threads; i++) pool.submit(() -> {
                start.await();
                while (System.nanoTime() < deadline) {
                    try { body.run(); } catch (Throwable t) { errors.add(t); }
                }
                return null;
            });
            start.countDown();
        }
        return List.copyOf(errors);
    }
}
