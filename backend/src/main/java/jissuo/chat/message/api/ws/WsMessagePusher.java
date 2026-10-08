package jissuo.chat.message.api.ws;

import java.util.List;
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
    private final WsFrameSender sender;
    private final JsonMapper json;

    public WsMessagePusher(WsSessionRegistry sessions, WsFrameSender sender, JsonMapper json) {
        this.sessions = sessions;
        this.sender = sender;
        this.json = json;
    }

    @Override
    public void push(List<Long> userIds, Message message, DeliveryOrigin origin) {
        // ADR-131: 본문 전체를 한 번만 직렬화해 모든 탭에 같은 프레임을 보낸다
        TextMessage frame = new TextMessage(json.writeValueAsString(MessageFrame.of(MessageResponse.from(message))));
        for (long userId : userIds) {
            // F3: 스레드 안전하지 않은 목록을 복사하지 않고 그대로 순회한다 (재현 전)
            for (WebSocketSession session : sessions.sessionsOf(userId)) {
                sender.send(session, frame, origin);
            }
        }
    }
}
