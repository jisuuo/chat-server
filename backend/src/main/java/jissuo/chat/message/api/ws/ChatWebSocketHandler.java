package jissuo.chat.message.api.ws;

import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;

/**
 * ADR-002: 순수 WebSocketHandler. TextWebSocketHandler를 상속하지 않는 이유는 handleMessage가 handleTextMessage를
 * 자기 호출해 AOP 프록시(계획 7 세부 7B)가 가로채지 못하기 때문이다.
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private final WsSessionRegistry sessions;
    private final ChatFrameHandler frames;

    public ChatWebSocketHandler(WsSessionRegistry sessions, ChatFrameHandler frames) {
        this.sessions = sessions;
        this.frames = frames;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(userOf(session).id(), session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        // 계획 7 세부 3: 텍스트만 처리한다. 바이너리·pong은 무시한다(ping/pong 없음, F5)
        if (message instanceof TextMessage text) {
            frames.handle(session, text.getPayload());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 오류 뒤에는 컨테이너가 연결을 닫고 afterConnectionClosed를 부르므로 여기서 지우지 않는다
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        sessions.remove(userOf(session).id(), session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    static AuthUser userOf(WebSocketSession session) {
        return (AuthUser) session.getAttributes().get(QueryUserIdHandshakeInterceptor.ATTRIBUTE);
    }
}
