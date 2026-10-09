package jissuo.chat.experiment.cluster;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

interface ReconnectPolicy {
    Duration delay(int attempt);
}

record Fixed(Duration delay) implements ReconnectPolicy {
    @Override
    public Duration delay(int attempt) {
        return delay;
    }
}

record FullJitter(Duration base, Duration cap, RandomGenerator random) implements ReconnectPolicy {
    @Override
    public Duration delay(int attempt) {
        long boundMillis = Math.min(cap.toMillis(),
                (long) Math.min(Long.MAX_VALUE, base.toMillis() * Math.pow(2, attempt)));
        return Duration.ofMillis(random.nextLong(boundMillis));
    }
}

/** 브라우저의 연결 종료 → 정책에 따른 대기 → 재연결 → 최신 조회 경로를 실험에서 반복한다. */
final class StormClient implements AutoCloseable {

    record Event(long nanos, String type, String detail) {}

    private static final StandardWebSocketClient WEBSOCKETS = new StandardWebSocketClient();

    private final long userId;
    private final long roomId;
    private final ReconnectPolicy policy;
    private final ClusterHttp http;
    private final ScheduledExecutorService timer;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private volatile WebSocketSession current;
    private volatile int failedAttempts;

    StormClient(long userId, long roomId, ReconnectPolicy policy, ClusterHttp http,
                ScheduledExecutorService timer) {
        this.userId = userId;
        this.roomId = roomId;
        this.policy = policy;
        this.http = http;
        this.timer = timer;
    }

    void start() {
        running = true;
        timer.execute(this::connect);
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    boolean isOpen() {
        WebSocketSession session = current;
        return session != null && session.isOpen();
    }

    long userId() { return userId; }
    long roomId() { return roomId; }

    private void connect() {
        if (!running) return;
        events.add(new Event(System.nanoTime(), "ATTEMPT", Integer.toString(failedAttempts)));
        URI uri = URI.create("ws://localhost:" + ClusterControl.NGINX + "/ws?userId=" + userId);
        AtomicBoolean finished = new AtomicBoolean();
        try {
            WEBSOCKETS.execute(new TextWebSocketHandler() {
                @Override
                public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                    if (!running) {
                        session.close();
                        return;
                    }
                    current = session;
                    failedAttempts = 0;
                    events.add(new Event(System.nanoTime(), "OPENED", session.getId()));
                    // ADR-145: 재연결 직후의 최신 조회를 흉내 낸다. 실험 중 새 메시지는 보내지 않는다.
                    Thread.startVirtualThread(() -> recover());
                }

                @Override
                public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                    if (current == session) current = null;
                    if (finished.compareAndSet(false, true)) {
                        events.add(new Event(System.nanoTime(), "CLOSED", Integer.toString(status.getCode())));
                        retry();
                    }
                }

                @Override
                public void handleTransportError(WebSocketSession session, Throwable error) {
                    if (current == session) current = null;
                    if (finished.compareAndSet(false, true)) {
                        events.add(new Event(System.nanoTime(), "CLOSED", "transport:" + error.getClass().getSimpleName()));
                        retry();
                    }
                }
            }, ClusterControl.browserHeaders(), uri).whenComplete((session, error) -> {
                if (error != null && finished.compareAndSet(false, true)) {
                    events.add(new Event(System.nanoTime(), "FAILED", error.getClass().getSimpleName()
                            + ":" + String.valueOf(error.getMessage()).replace(',', ' ')));
                    retry();
                }
            });
        } catch (RuntimeException error) {
            if (finished.compareAndSet(false, true)) {
                events.add(new Event(System.nanoTime(), "FAILED", error.getClass().getSimpleName()
                        + ":" + String.valueOf(error.getMessage()).replace(',', ' ')));
                retry();
            }
        }
    }

    private void recover() {
        long started = System.nanoTime();
        try {
            int status = http.latest(userId, roomId).statusCode();
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            events.add(new Event(System.nanoTime(), "RECOVERED", status + ":" + millis));
        } catch (Exception error) {
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            events.add(new Event(System.nanoTime(), "RECOVERED", "error:" + millis + ":"
                    + error.getClass().getSimpleName()));
        }
    }

    private void retry() {
        if (!running) return;
        Duration delay = policy.delay(failedAttempts++);
        timer.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws Exception {
        running = false;
        WebSocketSession session = current;
        if (session != null && session.isOpen()) session.close();
    }
}
