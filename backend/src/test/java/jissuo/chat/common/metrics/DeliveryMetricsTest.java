package jissuo.chat.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import jissuo.chat.message.application.MessageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@ActiveProfiles("mysql")
class DeliveryMetricsTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;
    @Autowired MessageService messages;

    @Test
    void REST와_WS_전송의_단계와_전체_시간을_통로별로_노출한다() throws Exception {
        ChatHttp http = new ChatHttp(port, json);
        long sender = addUser();
        long room = http.createRoom(sender, "지표");
        try (WsTestClient tab = WsTestClient.connect(port, sender)) {
            await().atMost(Duration.ofSeconds(5)).until(() -> meters.get("chat.ws.sessions").gauge().value() >= 1);
            assertThat(http.send(sender, room, "rest").statusCode()).isEqualTo(201);
            tab.next();
            tab.send("{\"type\":\"send\",\"roomId\":" + room + ",\"content\":\"ws\"}");
            tab.next();
            messages.send(sender, room, "internal");
            tab.next();
        }

        String body = scrape();
        for (String transport : new String[] {"rest", "ws"}) {
            for (String stage : new String[] {"receive", "save", "fanout", "push"}) {
                assertThat(hasLine(body, "chat_delivery_stage_seconds_count", "stage=\"" + stage + "\"",
                        "transport=\"" + transport + "\"")).as(stage + "/" + transport).isTrue();
            }
            assertThat(hasLine(body, "chat_delivery_total_seconds_count", "transport=\"" + transport + "\""))
                    .as("total/" + transport).isTrue();
        }
        assertThat(body).contains("chat_delivery_total_seconds_bucket");
        assertThat(hasLine(body, "chat_ws_frames_total", "type=\"send\"")).isTrue();
        assertThat(hasLine(body, "chat_delivery_stage_seconds_count", "stage=\"save\"",
                "transport=\"internal\"")).isTrue();
    }

    private static boolean hasLine(String body, String name, String... labels) {
        return body.lines().filter(line -> line.startsWith(name + "{"))
                .anyMatch(line -> java.util.Arrays.stream(labels).allMatch(line::contains));
    }

    private String scrape() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private long addUser() {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES ('지표', :at)")
                .param("at", LocalDateTime.now(ZoneOffset.UTC)).update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
