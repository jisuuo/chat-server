package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.io.IOException;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.common.metrics.DeliveryStage;
import jissuo.chat.message.api.SendMessageRequest;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 프레임 처리를 별도 빈의 public 메서드에 둔다. 핸들러 안에서 자기 호출하면 AOP가 가로채지 못한다
 * (ADR-135·136).
 */
@Component
public class ChatFrameHandler {

    private final MessageService messages;
    private final JsonMapper json;
    private final Validator validator;
    private final MeterRegistry meters;

    public ChatFrameHandler(MessageService messages, JsonMapper json, Validator validator, MeterRegistry meters) {
        this.messages = messages;
        this.json = json;
        this.validator = validator;
        this.meters = meters;
    }

    @DeliveryStage(value = "receive", transport = "ws")
    public FrameOutcome handle(WebSocketSession session, String payload) throws IOException {
        DeliveryOrigin origin = DeliveryOrigin.start("ws");
        AuthUser user = ChatWebSocketHandler.userOf(session);
        SendFrame frame;
        try {
            JsonNode body = json.readTree(payload);
            JsonNode roomId = body == null ? null : body.get("roomId");
            // 정수가 아닌 JSON 숫자를 Long으로 읽으면 소수점이 잘려 다른 방에 전송될 수 있다.
            if (roomId == null || !roomId.isIntegralNumber() || !roomId.canConvertToLong()) {
                return reject(session, "invalid", null, ErrorCode.INVALID_REQUEST);
            }
            frame = json.treeToValue(body, SendFrame.class);
        } catch (JacksonException e) {
            return reject(session, "invalid", null, ErrorCode.INVALID_REQUEST);
        }
        // ADR-045·048·052: REST 본문과 같은 입력 규칙을 한 곳에서 검사한다
        if (frame == null || !"send".equals(frame.type()) || frame.roomId() == null
                || !validator.validate(new SendMessageRequest(frame.content())).isEmpty()) {
            return reject(session, "invalid", frame == null ? null : frame.roomId(), ErrorCode.INVALID_REQUEST);
        }
        count("send");
        try {
            messages.send(user.id(), frame.roomId(), frame.content(), origin);
            return FrameOutcome.ok("send", frame.roomId());
            // AFTER_COMMIT push 예외(F44)는 서비스 본문 예외로 잡지 않는다
        } catch (ChatException e) {
            return reject(session, "send", frame.roomId(), e.errorCode());
        }
    }

    private FrameOutcome reject(WebSocketSession session, String type, Long roomId, ErrorCode code) throws IOException {
        if ("invalid".equals(type)) {
            count("invalid");
        }
        // 같은 세션에 push와 오류를 동시에 쓰는 경우(F43)는 재현 전까지 그대로 둔다
        session.sendMessage(new TextMessage(json.writeValueAsString(ErrorFrame.of(roomId, code))));
        count("error");
        return FrameOutcome.rejected(type, roomId, code);
    }

    private void count(String type) {
        meters.counter("chat.ws.frames", "type", type).increment();
    }
}
