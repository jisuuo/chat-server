package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class ChatFrameHandlerTest {

    final MessageService messages = mock(MessageService.class);
    final JsonMapper json = JsonMapper.builder().build();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ChatFrameHandler handler = new ChatFrameHandler(messages, json,
            Validation.buildDefaultValidatorFactory().getValidator(), meters);
    final WebSocketSession session = mock(WebSocketSession.class);

    @BeforeEach
    void setUp() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7));
        when(session.getAttributes()).thenReturn(attributes);
    }

    @Test
    void send_프레임은_서비스에_ws_통로로_넘기고_응답_프레임은_보내지_않는다() throws Exception {
        when(messages.send(eq(7L), eq(3L), eq("안녕"), any(DeliveryOrigin.class)))
                .thenReturn(new Message(1, 3, 7, new MessageContent("안녕"), Instant.now()));

        FrameOutcome outcome = handler.handle(session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"안녕\"}");

        assertThat(outcome).isEqualTo(FrameOutcome.ok("send", 3L));
        ArgumentCaptor<DeliveryOrigin> origin = ArgumentCaptor.forClass(DeliveryOrigin.class);
        verify(messages).send(eq(7L), eq(3L), eq("안녕"), origin.capture());
        assertThat(origin.getValue().transport()).isEqualTo("ws");
        // 계획 7 세부 3: 응답 짝 맞춤 없이 보낸 사람도 message push로 받는다
        verify(session, never()).sendMessage(any());
        assertThat(meters.get("chat.ws.frames").tag("type", "send").counter().count()).isEqualTo(1);
    }

    @Test
    void 서비스가_거절하면_보낸_세션에만_error_프레임을_보내고_연결은_유지한다() throws Exception {
        when(messages.send(anyLong(), anyLong(), anyString(), any(DeliveryOrigin.class)))
                .thenThrow(new ChatException(ErrorCode.NOT_A_MEMBER));

        FrameOutcome outcome = handler.handle(session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"안녕\"}");

        assertThat(outcome).isEqualTo(FrameOutcome.rejected("send", 3L, ErrorCode.NOT_A_MEMBER));
        assertThat(sentFrame()).isEqualTo(
                "{\"type\":\"error\",\"roomId\":3,\"code\":\"NOT_A_MEMBER\",\"message\":\"이 채팅방의 멤버가 아닙니다.\"}");
        verify(session, never()).close(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "[]", "{\"type\":\"hello\"}", "{\"type\":\"send\",\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":\"abc\",\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":1.9,\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":\"3\",\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":3}", "{\"type\":\"send\",\"roomId\":3,\"content\":\"\"}",
            "{\"type\":\"send\",\"roomId\":3,\"content\":\"a\\u0000b\"}"})
    void 잘못된_프레임은_INVALID_REQUEST_error를_보내고_서비스를_부르지_않는다(String payload) throws Exception {
        FrameOutcome outcome = handler.handle(session, payload);

        assertThat(outcome.result()).isEqualTo("INVALID_REQUEST");
        assertThat(json.readTree(sentFrame()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verify(messages, never()).send(anyLong(), anyLong(), anyString(), any(DeliveryOrigin.class));
    }

    private String sentFrame() throws Exception {
        ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(frame.capture());
        return frame.getValue().getPayload();
    }
}
