package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jissuo.chat.common.metrics.DeliveryTimingAspect;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WsFrameSenderTest {

    @Test
    void 닫힌_세션은_push_시간이나_전송_횟수에_포함하지_않는다() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WsFrameSender sender = timedSender(meters);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(false);

        assertThat(sender.send(session, new TextMessage("message"), DeliveryOrigin.start("ws"))).isFalse();

        assertThat(meters.find("chat.delivery.stage").tags("stage", "push", "transport", "ws").timer()).isNull();
        assertThat(meters.find("chat.ws.frames").counter()).isNull();
    }

    @Test
    void 열린_세션을_보내면_push_시간과_전송_횟수를_기록한다() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WsFrameSender sender = timedSender(meters);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        TextMessage frame = new TextMessage("message");

        assertThat(sender.send(session, frame, DeliveryOrigin.start("ws"))).isTrue();

        verify(session).sendMessage(frame);
        assertThat(meters.get("chat.delivery.stage").tags("stage", "push", "transport", "ws").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("chat.ws.frames").tag("type", "message").counter().count()).isEqualTo(1);
    }

    private static WsFrameSender timedSender(SimpleMeterRegistry meters) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new WsFrameSender(meters));
        factory.setProxyTargetClass(true);
        factory.addAspect(new DeliveryTimingAspect(meters));
        return factory.getProxy();
    }
}
