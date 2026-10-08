package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.common.metrics.DeliveryStage;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 세션 하나에 프레임 하나를 보낸다. push 단계 시간(계획 7 세부 7A)을 세션마다 재려고 별도 빈으로 둔다. */
@Component
public class WsFrameSender {

    private final MeterRegistry meters;

    public WsFrameSender(MeterRegistry meters) {
        this.meters = meters;
    }

    // F4: 받는 쪽이 읽지 않으면 이 호출이 막혀 뒤의 세션과 보낸 사람의 응답이 함께 늦어진다
    // F43: 다른 스레드가 같은 세션에 쓰는 중이면 예외가 난다. 둘 다 재현 전이라 그대로 둔다
    @DeliveryStage("push")
    public boolean send(WebSocketSession session, TextMessage frame, DeliveryOrigin origin) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(frame);
        } catch (IOException e) {
            // F44: 저장은 이미 커밋됐다. AFTER_COMMIT 리스너의 예외는 Spring이 로그에 남긴다
            throw new UncheckedIOException(e);
        }
        meters.counter("chat.ws.frames", "type", "message").increment();
        return true;
    }
}
