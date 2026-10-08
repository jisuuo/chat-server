package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class WsMessagePusherTest {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final WsSessionRegistry sessions = new WsSessionRegistry(meters);
    final WsOutboundQueue outbound = mock(WsOutboundQueue.class);
    final WsMessagePusher pusher = new WsMessagePusher(sessions, outbound, JsonMapper.builder().build(), meters);
    final Message message = new Message(1, 3, 1, new MessageContent("hello"), Instant.now());

    @Test
    void 모든_세션의_실제_전송이_끝난_뒤에만_전체_지연을_기록한다() {
        WebSocketSession one = openSession();
        WebSocketSession two = openSession();
        sessions.add(1, one);
        sessions.add(2, two);
        AtomicInteger enqueued = new AtomicInteger();
        @SuppressWarnings("unchecked")
        List<Consumer<Boolean>> completions = new java.util.concurrent.CopyOnWriteArrayList<>();
        doAnswer(call -> {
            enqueued.incrementAndGet();
            completions.add(call.getArgument(3));
            return null;
        }).when(outbound).message(any(), any(), any(), any());

        pusher.push(List.of(1L, 2L), message, DeliveryOrigin.start("rest"));

        assertThat(enqueued).hasValue(2);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
        completions.get(0).accept(true);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
        completions.get(1).accept(true);
        assertThat(meters.get("chat.delivery.total").tag("transport", "rest").timer().count()).isEqualTo(1);
        assertThat(meters.get("chat.delivery.total").tag("transport", "rest").timer()
                .totalTime(TimeUnit.NANOSECONDS)).isPositive();
    }

    @Test
    void 한_세션이라도_실패하면_완료_지연_대신_실패를_기록한다() {
        sessions.add(1, openSession());
        sessions.add(2, openSession());
        AtomicInteger sends = new AtomicInteger();
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Consumer<Boolean> completion = call.getArgument(3);
            completion.accept(sends.incrementAndGet() != 1);
            return null;
        }).when(outbound).message(any(), any(), any(), any());

        pusher.push(List.of(1L, 2L), message, DeliveryOrigin.start("ws"));

        assertThat(sends).hasValue(2);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
        assertThat(meters.get("chat.delivery.failed").tag("transport", "ws").counter().count()).isEqualTo(1);
    }

    @Test
    void 수신자_수집이_실패해도_메시지당_실패를_한_번_기록한다() {
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.isOpen()).thenThrow(new IllegalStateException("session failed"));
        sessions.add(1, broken);

        assertThatThrownBy(() -> pusher.push(List.of(1L), message, DeliveryOrigin.start("rest")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("chat.delivery.failed").tag("transport", "rest").counter().count()).isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
    }

    @Test
    void 일부_등록_뒤_동기_오류가_나도_성공과_실패를_중복_집계하지_않는다() {
        sessions.add(1, openSession());
        sessions.add(2, openSession());
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                @SuppressWarnings("unchecked")
                Consumer<Boolean> completion = call.getArgument(3);
                completion.accept(true);
            } else {
                throw new IllegalStateException("enqueue failed");
            }
            return null;
        }).when(outbound).message(any(), any(), any(), any());

        assertThatThrownBy(() -> pusher.push(List.of(1L, 2L), message, DeliveryOrigin.start("ws")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("chat.delivery.failed").tag("transport", "ws").counter().count()).isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
    }

    private static WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
