package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WsOutboundQueueTest {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final WsFrameSender sender = mock(WsFrameSender.class);
    final WsOutboundQueue outbound = new WsOutboundQueue(sender, meters);
    final DeliveryOrigin origin = DeliveryOrigin.start("rest");

    @Test
    void 느린_세션이_막혀도_다른_세션은_전송하고_같은_세션은_순서를_지킨다() throws Exception {
        WebSocketSession slow = session("slow");
        WebSocketSession fast = session("fast");
        outbound.register(slow);
        outbound.register(fast);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        when(sender.send(eq(slow), any(), any())).thenAnswer(call -> {
            slowStarted.countDown();
            releaseSlow.await(5, TimeUnit.SECONDS);
            return true;
        });
        List<String> fastOrder = new CopyOnWriteArrayList<>();
        when(sender.send(eq(fast), any(), any())).thenAnswer(call -> {
            fastOrder.add(call.getArgument(1, TextMessage.class).getPayload());
            return true;
        });
        try {
            outbound.message(slow, new TextMessage("blocked"), origin, ignored -> {});
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            outbound.message(fast, new TextMessage("first"), origin, ignored -> {});
            outbound.message(fast, new TextMessage("second"), origin, ignored -> {});
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(fastOrder).containsExactly("first", "second"));
        } finally {
            releaseSlow.countDown();
            outbound.unregister(slow);
            outbound.unregister(fast);
        }
    }

    @Test
    void 큐가_가득_차면_해당_세션만_닫고_대기_프레임을_실패로_끝낸다() throws Exception {
        WebSocketSession slow = session("slow");
        outbound.register(slow);
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        when(sender.send(eq(slow), any(), any())).thenAnswer(call -> {
            slowStarted.countDown();
            releaseSlow.await(5, TimeUnit.SECONDS);
            return true;
        });
        AtomicInteger failed = new AtomicInteger();
        try {
            outbound.message(slow, new TextMessage("active"), origin, ignored -> {});
            assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i <= WsOutboundQueue.MAX_PENDING_FRAMES; i++) {
                outbound.message(slow, new TextMessage("pending " + i), origin, sent -> {
                    if (!sent) {
                        failed.incrementAndGet();
                    }
                });
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(failed).hasValue(WsOutboundQueue.MAX_PENDING_FRAMES + 1));
            verify(slow, org.mockito.Mockito.timeout(5000)).close(new CloseStatus(1013, "outbound queue full"));
            assertThat(meters.get("chat.ws.outbound.dropped").tag("reason", "queue_full")
                    .counter().count()).isEqualTo(WsOutboundQueue.MAX_PENDING_FRAMES + 1);
        } finally {
            releaseSlow.countDown();
            outbound.unregister(slow);
        }
    }

    @Test
    void 종료가_막혀도_같은_세션에_동시_close를_만들지_않는다() throws Exception {
        WebSocketSession session = session("blocked-close");
        outbound.register(session);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            calls.incrementAndGet();
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(session).close(any(CloseStatus.class));
        try {
            outbound.close(session, CloseStatus.SERVER_ERROR);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            outbound.close(session, CloseStatus.SERVER_ERROR);
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
                    .untilAsserted(() -> assertThat(calls).hasValue(1));
        } finally {
            release.countDown();
            outbound.unregister(session);
        }
        verify(session, times(1)).close(CloseStatus.SERVER_ERROR);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
