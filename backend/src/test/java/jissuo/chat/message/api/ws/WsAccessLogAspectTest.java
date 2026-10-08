package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.Validation;
import java.io.IOException;
import java.util.Map;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.common.RequestLogContextFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class WsAccessLogAspectTest {
    final WsAccessLogAspect aspect = new WsAccessLogAspect(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    final WebSocketSession session = mock(WebSocketSession.class);

    @BeforeEach
    void setUp() {
        appender.start();
        logger().addAppender(appender);
        when(session.getAttributes()).thenReturn(Map.of(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7)));
        when(session.getId()).thenReturn("s-1");
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
        MDC.clear();
    }

    @Test
    void 프레임마다_새_요청_ID를_넣고_결과를_한_줄로_남긴다() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {session, "payload"});
        when(pjp.proceed()).thenAnswer(invocation -> {
            assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNotNull();
            assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isEqualTo("7");
            return FrameOutcome.rejected("send", 3L, ErrorCode.NOT_A_MEMBER);
        });
        aspect.frame(pjp);
        aspect.frame(pjp);

        assertThat(appender.list).hasSize(2);
        ILoggingEvent first = appender.list.get(0);
        assertThat(value(first, "event")).isEqualTo("frame");
        assertThat(value(first, "sessionId")).isEqualTo("s-1");
        assertThat(value(first, "frameType")).isEqualTo("send");
        assertThat(value(first, "roomId")).isEqualTo("3");
        assertThat(value(first, "result")).isEqualTo("NOT_A_MEMBER");
        assertThat(value(first, "durationMs")).isNotNull();
        assertThat(first.getMDCPropertyMap()).containsEntry("userId", "7").containsKey("requestId");
        assertThat(first.getMDCPropertyMap().get("requestId"))
                .isNotEqualTo(appender.list.get(1).getMDCPropertyMap().get("requestId"));
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 종료_코드를_남긴다() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {session, CloseStatus.GOING_AWAY});
        aspect.close(pjp);
        assertThat(value(appender.list.get(0), "event")).isEqualTo("close");
        assertThat(value(appender.list.get(0), "closeCode")).isEqualTo("1001");
        assertThat(value(appender.list.get(0), "result")).isEqualTo("ok");
    }

    @Test
    void 프레임_처리_예외에도_종류와_방_ID를_남긴다() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"hello\"}"});
        when(pjp.proceed()).thenThrow(new IOException("전송 실패"));

        assertThatThrownBy(() -> aspect.frame(pjp)).isInstanceOf(IOException.class).hasMessage("전송 실패");

        ILoggingEvent event = appender.list.get(0);
        assertThat(value(event, "result")).isEqualTo("exception");
        assertThat(value(event, "frameType")).isEqualTo("send");
        assertThat(value(event, "roomId")).isEqualTo("3");
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 유효하지_않은_프레임에서_오류_전송이_실패해도_invalid로_기록한다() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getArgs()).thenReturn(new Object[] {session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"\"}"});
        when(pjp.proceed()).thenThrow(new IOException("오류 프레임 실패"));

        assertThatThrownBy(() -> aspect.frame(pjp)).isInstanceOf(IOException.class).hasMessage("오류 프레임 실패");

        ILoggingEvent event = appender.list.get(0);
        assertThat(value(event, "result")).isEqualTo("exception");
        assertThat(value(event, "frameType")).isEqualTo("invalid");
        assertThat(value(event, "roomId")).isEqualTo("3");
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger("WS_ACCESS");
    }
}
