package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class WsAccessLogTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
    }

    @Test
    void 접속_프레임_종료를_남기고_이벤트마다_요청_ID가_다르다() throws Exception {
        try (WsTestClient tab = WsTestClient.connect(port, 7)) {
            tab.send("{");
            tab.next();
            tab.send("{\"type\":\"send\",\"roomId\":999999,\"content\":\"없는 방\"}");
            tab.next();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> events().size() >= 4);
        List<ILoggingEvent> events = events();
        assertThat(events).extracting(e -> value(e, "event")).containsExactly("connect", "frame", "frame", "close");
        assertThat(value(events.get(1), "frameType")).isEqualTo("invalid");
        assertThat(value(events.get(1), "result")).isEqualTo("INVALID_REQUEST");
        assertThat(value(events.get(2), "frameType")).isEqualTo("send");
        assertThat(value(events.get(2), "result")).isEqualTo("NOT_A_MEMBER");
        assertThat(value(events.get(3), "closeCode")).isEqualTo("1000");
        assertThat(events).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("userId", "7"));
        assertThat(events.stream().map(e -> e.getMDCPropertyMap().get("requestId")).distinct()).hasSize(4);
    }

    private List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger("WS_ACCESS");
    }
}
