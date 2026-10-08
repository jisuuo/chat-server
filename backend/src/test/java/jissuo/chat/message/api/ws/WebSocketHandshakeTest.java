package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class WebSocketHandshakeTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    final ListAppender<ILoggingEvent> audit = new ListAppender<>();

    @BeforeEach
    void setUp() {
        audit.start();
        auditLogger().addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        auditLogger().detachAppender(audit);
    }

    @Test
    void 올바른_id로_연결하면_세션_게이지가_늘고_닫으면_줄어든다() throws Exception {
        double before = sessions();
        // ADR-005: 형식만 본다. 사용자 7이 DB에 없어도 연결된다
        try (WsTestClient client = WsTestClient.connect(port, 7)) {
            assertThat(client.isOpen()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before + 1);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "userId=", "userId=abc", "userId=007"})
    void 쿼리의_id가_없거나_형식이_틀리면_401로_거절하고_감사에_남긴다(String query) {
        double before = sessions();

        assertThatThrownBy(() -> WsTestClient.connectRaw(port, query)).hasStackTraceContaining("401");

        await().atMost(Duration.ofSeconds(5)).until(() -> auditEvents().stream().anyMatch(e ->
                "AUTHENTICATION_FAILED".equals(value(e, "action")) && "/ws".equals(value(e, "path"))));
        assertThat(sessions()).isEqualTo(before);
    }

    private double sessions() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    private List<ILoggingEvent> auditEvents() {
        // 서버 스레드가 쓰는 중에 읽지 않도록 appender 잠금(AppenderBase.doAppend와 같은 객체)으로 복사한다
        synchronized (audit) {
            return List.copyOf(audit.list);
        }
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs() == null ? null : event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key)).map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger("AUDIT");
    }
}
