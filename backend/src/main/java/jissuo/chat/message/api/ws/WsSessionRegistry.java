package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 사용자 → 탭들의 세션. ADR-134: 흔한 구현 그대로 스레드 안전하지 않은 HashMap·ArrayList를 쓴다.
 * 동시 접속·종료·push 순회가 겹칠 때의 문제(F3)를 재현한 뒤 사용자와 보완을 정한다.
 */
@Component
public class WsSessionRegistry {

    private final Map<Long, List<WebSocketSession>> sessions = new HashMap<>();

    public WsSessionRegistry(MeterRegistry meters) {
        Gauge.builder("chat.ws.sessions", this, WsSessionRegistry::count).register(meters);
    }

    public void add(long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, id -> new ArrayList<>()).add(session);
    }

    public void remove(long userId, WebSocketSession session) {
        List<WebSocketSession> tabs = sessions.get(userId);
        if (tabs == null) {
            return;
        }
        tabs.remove(session);
        if (tabs.isEmpty()) {
            sessions.remove(userId);
        }
    }

    public List<WebSocketSession> sessionsOf(long userId) {
        return sessions.getOrDefault(userId, List.of());
    }

    public int count() {
        int count = 0;
        for (List<WebSocketSession> tabs : sessions.values()) {
            count += tabs.size();
        }
        return count;
    }
}
