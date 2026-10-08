package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/** 세션별로 쓰기를 직렬화하고 느린 연결의 대기 프레임 수를 제한한다. */
@Component
public class WsOutboundQueue {
    private static final Logger log = LoggerFactory.getLogger(WsOutboundQueue.class);
    static final int MAX_PENDING_FRAMES = 32;
    private static final CloseStatus OVERLOADED = new CloseStatus(1013, "outbound queue full");

    private final Map<WebSocketSession, SessionQueue> queues = new ConcurrentHashMap<>();
    private final WsFrameSender sender;
    private final MeterRegistry meters;

    public WsOutboundQueue(WsFrameSender sender, MeterRegistry meters) {
        this.sender = sender;
        this.meters = meters;
    }

    public void register(WebSocketSession session) {
        queues.put(session, new SessionQueue(session));
    }

    public void unregister(WebSocketSession session) {
        SessionQueue queue = queues.remove(session);
        if (queue != null) {
            queue.stop();
        }
    }

    public void message(WebSocketSession session, TextMessage frame, DeliveryOrigin origin,
                        Consumer<Boolean> completion) {
        submit(session, new PendingFrame(frame, origin, completion));
    }

    public void error(WebSocketSession session, TextMessage frame) {
        submit(session, new PendingFrame(frame, null, ignored -> {}));
    }

    public void ping(WebSocketSession session) {
        submit(session, new PendingFrame(new PingMessage(), null, ignored -> {}));
    }

    public void close(WebSocketSession session, CloseStatus status) {
        SessionQueue queue = queues.get(session);
        if (queue != null) {
            queue.stop();
            queue.closeAsync(status);
        }
    }

    private void submit(WebSocketSession session, PendingFrame frame) {
        SessionQueue queue = queues.get(session);
        if (queue == null) {
            frame.completion().accept(false);
        } else {
            queue.offer(frame);
        }
    }

    private record PendingFrame(WebSocketMessage<?> frame, DeliveryOrigin origin, Consumer<Boolean> completion) {}

    private final class SessionQueue {
        private final WebSocketSession session;
        private final ArrayDeque<PendingFrame> pending = new ArrayDeque<>();
        private boolean draining;
        private boolean stopped;
        private boolean closing;

        private SessionQueue(WebSocketSession session) {
            this.session = session;
        }

        void offer(PendingFrame frame) {
            List<PendingFrame> discarded = List.of();
            boolean start = false;
            boolean overflow = false;
            synchronized (this) {
                if (stopped) {
                    discarded = List.of(frame);
                } else if (pending.size() >= MAX_PENDING_FRAMES) {
                    discarded = new ArrayList<>(pending);
                    discarded.add(frame);
                    pending.clear();
                    stopped = true;
                    overflow = true;
                } else {
                    pending.addLast(frame);
                    if (!draining) {
                        draining = true;
                        start = true;
                    }
                }
            }
            discarded.forEach(item -> item.completion().accept(false));
            if (overflow) {
                meters.counter("chat.ws.outbound.dropped", "reason", "queue_full").increment(discarded.size());
                closeAsync(OVERLOADED);
            }
            if (start) {
                Thread.ofVirtual().name("ws-outbound-" + session.getId()).start(this::drain);
            }
        }

        void stop() {
            List<PendingFrame> discarded;
            synchronized (this) {
                stopped = true;
                discarded = new ArrayList<>(pending);
                pending.clear();
            }
            discarded.forEach(item -> item.completion().accept(false));
        }

        private void drain() {
            while (true) {
                PendingFrame item;
                synchronized (this) {
                    item = pending.pollFirst();
                    if (item == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    boolean sent = item.frame() instanceof PingMessage ping
                            ? sender.sendPing(session, ping)
                            : item.origin() == null
                                    ? sender.sendError(session, (TextMessage) item.frame())
                                    : sender.send(session, (TextMessage) item.frame(), item.origin());
                    item.completion().accept(sent);
                } catch (RuntimeException e) {
                    item.completion().accept(false);
                    log.warn("WebSocket outbound send failed, sessionId={}", session.getId(), e);
                    stop();
                    closeAsync(CloseStatus.SERVER_ERROR);
                    return;
                }
            }
        }

        void closeAsync(CloseStatus status) {
            synchronized (this) {
                if (closing) {
                    return;
                }
                closing = true;
            }
            try {
                Thread.ofVirtual().name("ws-close-" + session.getId()).start(() -> {
                    try {
                        session.close(status);
                    } catch (IOException | IllegalStateException e) {
                        log.debug("WebSocket close failed, sessionId={}", session.getId(), e);
                    } finally {
                        synchronized (this) {
                            closing = false;
                        }
                    }
                });
            } catch (RuntimeException e) {
                synchronized (this) {
                    closing = false;
                }
                throw e;
            }
        }
    }
}
