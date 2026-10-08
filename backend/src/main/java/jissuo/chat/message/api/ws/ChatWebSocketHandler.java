package jissuo.chat.message.api.ws;

import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * ADR-002: 순수 WebSocketHandler. TextWebSocketHandler를 상속하지 않는 이유는 handleMessage가 handleTextMessage를
 * 자기 호출해 AOP 프록시(계획 7 세부 7B)가 가로채지 못하기 때문이다.
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private final WsSessionRegistry sessions;

    public ChatWebSocketHandler(WsSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(userOf(session).id(), session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // 작업 4에서 받은 프레임을 ChatFrameHandler로 넘긴다
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
