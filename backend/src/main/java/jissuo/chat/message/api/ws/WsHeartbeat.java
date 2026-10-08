package jissuo.chat.message.api.ws;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 조용히 끊긴 경로를 pong 부재로 감지한다. */
@Component
public class WsHeartbeat {
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final Duration CLOSE_GRACE = Duration.ofSeconds(30);
    private static final CloseStatus TIMED_OUT = new CloseStatus(1001, "heartbeat timeout");

    private final Map<WebSocketSession, Long> lastPong = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, Long> closingSince = new ConcurrentHashMap<>();
    private final WsOutboundQueue outbound;
    private final WsSessionRegistry sessions;
    private final LongSupplier clock;

    @Autowired
    public WsHeartbeat(WsOutboundQueue outbound, WsSessionRegistry sessions) {
        this(outbound, sessions, System::nanoTime);
    }

    WsHeartbeat(WsOutboundQueue outbound, WsSessionRegistry sessions, LongSupplier clock) {
        this.outbound = outbound;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void register(WebSocketSession session) {
        closingSince.remove(session);
        lastPong.put(session, clock.getAsLong());
    }

    public void unregister(WebSocketSession session) {
        lastPong.remove(session);
        closingSince.remove(session);
    }

    public void pong(WebSocketSession session) {
        lastPong.computeIfPresent(session, (ignored, previous) ->
                closingSince.containsKey(session) ? previous : clock.getAsLong());
    }

    @Scheduled(fixedDelay = 10_000)
    public void tick() {
        long now = clock.getAsLong();
        for (WebSocketSession session : lastPong.keySet()) {
            lastPong.computeIfPresent(session, (ignored, last) -> {
                if (!session.isOpen()) {
                    // 종료 콜백이 오지 않아도 등록 정보를 정리한다. 콜백과 중복 실행돼도 안전하다.
                    cleanup(session);
                    return null;
                }
                Long closeStarted = closingSince.get(session);
                if (closeStarted != null && now - closeStarted >= CLOSE_GRACE.toNanos()) {
                    // close가 영원히 막혀도 저장소와 송신 큐에는 좀비 세션을 남기지 않는다.
                    cleanup(session);
                    return null;
                }
                if (now - last >= TIMEOUT.toNanos()) {
                    // pong과 같은 키를 원자적으로 갱신해, 직전에 온 pong을 지나치지 않는다.
                    closingSince.putIfAbsent(session, now);
                    outbound.close(session, TIMED_OUT);
                } else {
                    outbound.ping(session);
                }
                return last;
            });
        }
    }

    private void cleanup(WebSocketSession session) {
        closingSince.remove(session);
        sessions.remove(ChatWebSocketHandler.userOf(session).id(), session);
        outbound.unregister(session);
    }
}
