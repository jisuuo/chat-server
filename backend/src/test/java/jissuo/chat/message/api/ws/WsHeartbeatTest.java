package jissuo.chat.message.api.ws;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class WsHeartbeatTest {
    final AtomicLong now = new AtomicLong();
    final WsOutboundQueue outbound = mock(WsOutboundQueue.class);
    final WsSessionRegistry sessions = mock(WsSessionRegistry.class);
    final WsHeartbeat heartbeat = new WsHeartbeat(outbound, sessions, now::get);
    final WebSocketSession session = mock(WebSocketSession.class);

    @Test
    void pong이_오면_연결을_유지하고_30초_없으면_닫는다() {
        when(session.isOpen()).thenReturn(true);
        heartbeat.register(session);
        now.set(Duration.ofSeconds(10).toNanos());
        heartbeat.tick();
        verify(outbound).ping(session);

        now.set(Duration.ofSeconds(20).toNanos());
        heartbeat.pong(session);
        now.set(Duration.ofSeconds(40).toNanos());
        heartbeat.tick();
        verify(outbound, never()).close(session, new CloseStatus(1001, "heartbeat timeout"));

        now.set(Duration.ofSeconds(50).toNanos());
        heartbeat.tick();
        verify(outbound).close(session, new CloseStatus(1001, "heartbeat timeout"));
    }

    @Test
    void 종료된_세션은_다음_heartbeat에서_제외한다() {
        when(session.isOpen()).thenReturn(false);
        when(session.getAttributes()).thenReturn(Map.of(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(1)));
        heartbeat.register(session);
        heartbeat.tick();
        verify(outbound, never()).ping(session);
        verify(outbound, never()).close(session, new CloseStatus(1001, "heartbeat timeout"));
        verify(outbound).unregister(session);
        verify(sessions).remove(1, session);
    }

    @Test
    void 종료_요청_뒤에도_세션이_열려_있으면_다음_tick에_재시도한다() {
        when(session.isOpen()).thenReturn(true);
        heartbeat.register(session);
        now.set(Duration.ofSeconds(30).toNanos());
        heartbeat.tick();
        now.set(Duration.ofSeconds(40).toNanos());
        heartbeat.tick();

        verify(outbound, times(2)).close(session, new CloseStatus(1001, "heartbeat timeout"));
    }

    @Test
    void 종료_요청이_계속_막히면_유예_시간_뒤_등록을_정리한다() {
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(1)));
        heartbeat.register(session);
        now.set(Duration.ofSeconds(30).toNanos());
        heartbeat.tick();
        now.set(Duration.ofSeconds(60).toNanos());
        heartbeat.tick();

        verify(outbound, times(1)).close(session, new CloseStatus(1001, "heartbeat timeout"));
        verify(outbound).unregister(session);
        verify(sessions).remove(1, session);
    }
}
