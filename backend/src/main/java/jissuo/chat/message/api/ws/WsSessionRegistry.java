package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 사용자 → 탭들의 세션. ADR-142: 재현된 F3의 동시 수정 예외와 등록 유실을 막는다.
 */
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public WsSessionRegistry(MeterRegistry meters) {
        Gauge.builder("chat.ws.sessions", this, WsSessionRegistry::count).register(meters);
    }

    public void add(long userId, WebSocketSession session) {
        // F3: 집합을 얻은 뒤 추가하면 마지막 탭 삭제가 그 사이에 집합을 지도에서 떼어낼 수 있다.
        sessions.compute(userId, (id, tabs) -> {
            Set<WebSocketSession> updated = tabs == null ? ConcurrentHashMap.newKeySet() : tabs;
            updated.add(session);
            return updated;
        });
    }

    public void remove(long userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, tabs) -> {
            tabs.remove(session);
            return tabs.isEmpty() ? null : tabs;
        });
    }

    public Collection<WebSocketSession> sessionsOf(long userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    public int count() {
        int count = 0;
        for (Set<WebSocketSession> tabs : sessions.values()) {
            count += tabs.size();
        }
        return count;
    }
}
