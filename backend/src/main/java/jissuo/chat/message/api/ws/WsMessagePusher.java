package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jissuo.chat.message.api.MessageResponse;
import jissuo.chat.message.application.MessagePusher;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WsMessagePusher implements MessagePusher {

    private final WsSessionRegistry sessions;
    private final WsOutboundQueue outbound;
    private final JsonMapper json;
    private final MeterRegistry meters;

    public WsMessagePusher(WsSessionRegistry sessions, WsOutboundQueue outbound, JsonMapper json,
                           MeterRegistry meters) {
        this.sessions = sessions;
        this.outbound = outbound;
        this.json = json;
        this.meters = meters;
    }

    @Override
    public void push(List<Long> userIds, Message message, DeliveryOrigin origin) {
        DeliveryBatch batch = null;
        try {
            // ADR-131: 본문 전체를 한 번만 직렬화해 모든 탭에 같은 프레임을 보낸다
            TextMessage frame = new TextMessage(json.writeValueAsString(MessageFrame.of(MessageResponse.from(message))));
            List<WebSocketSession> recipients = new ArrayList<>();
            for (long userId : userIds) {
                // ADR-142: 접속·종료와 겹친 순회는 안전하지만 그 순간 바뀐 탭의 포함 여부는 보장하지 않는다.
                for (WebSocketSession session : sessions.sessionsOf(userId)) {
                    if (session.isOpen()) {
                        recipients.add(session);
                    }
                }
            }
            batch = new DeliveryBatch(origin, recipients.size());
            for (WebSocketSession session : recipients) {
                outbound.message(session, frame, origin, batch::complete);
            }
            batch.submitted();
        } catch (RuntimeException e) {
            if (batch == null) {
                meters.counter("chat.delivery.failed", "transport", origin.transport()).increment();
            } else {
                batch.abort();
            }
            throw e;
        }
    }

    private final class DeliveryBatch {
        private final DeliveryOrigin origin;
        private final AtomicInteger remaining;
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean recorded = new AtomicBoolean();
        private volatile boolean submitted;

        DeliveryBatch(DeliveryOrigin origin, int count) {
            this.origin = origin;
            this.remaining = new AtomicInteger(count);
        }

        void complete(boolean sent) {
            if (!sent) {
                failed.set(true);
            }
            remaining.decrementAndGet();
            finishIfReady();
        }

        void submitted() {
            submitted = true;
            finishIfReady();
        }

        void abort() {
            failed.set(true);
            submitted = true;
            if (recorded.compareAndSet(false, true)) {
                meters.counter("chat.delivery.failed", "transport", origin.transport()).increment();
            }
        }

        private void finishIfReady() {
            if (!submitted || remaining.get() != 0 || !recorded.compareAndSet(false, true)) {
                return;
            }
            if (failed.get()) {
                meters.counter("chat.delivery.failed", "transport", origin.transport()).increment();
            } else {
                Timer.builder("chat.delivery.total").tag("transport", origin.transport()).register(meters)
                        .record(System.nanoTime() - origin.startedNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }
}
