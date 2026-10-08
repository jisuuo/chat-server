package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.common.metrics.DeliveryStage;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;

/** 세션 하나에 프레임 하나를 보낸다. push 단계 시간(ADR-135)을 세션마다 재려고 별도 빈으로 둔다. */
@Component
public class WsFrameSender {

    private final MeterRegistry meters;

    public WsFrameSender(MeterRegistry meters) {
        this.meters = meters;
    }

    // ADR-143: 이 호출은 해당 세션의 송신 작업자에서만 실행한다.
    @DeliveryStage("push")
    public boolean send(WebSocketSession session, TextMessage frame, DeliveryOrigin origin) {
        return sendFrame(session, frame, "message");
    }

    public boolean sendError(WebSocketSession session, TextMessage frame) {
        return sendFrame(session, frame, "error");
    }

    public boolean sendPing(WebSocketSession session, PingMessage frame) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(frame);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return true;
    }

    private boolean sendFrame(WebSocketSession session, TextMessage frame, String type) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(frame);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        meters.counter("chat.ws.frames", "type", type).increment();
        return true;
    }
}
