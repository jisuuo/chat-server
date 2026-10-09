package jissuo.chat.experiment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jissuo.chat.ChatApplication;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F7: 같은 DB를 보는 두 서버의 WebSocket 세션 저장소가 서로 분리됐는지 확인한다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class TwoServerFanoutExperiment {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;
    @Autowired Environment environment;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 다른_서버의_수신자는_push를_받지_못하지만_DB에는_저장된다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        long sender = fixtures.user("cluster-sender");
        long a = fixtures.user("cluster-a");
        long b = fixtures.user("cluster-b");
        ChatHttp first = new ChatHttp(port, json);
        long room = first.createRoom(sender, "two-servers");
        first.join(a, room);
        first.join(b, room);

        // ADR-157: 두 컨텍스트가 동일한 Testcontainers MySQL을 사용하지만 세션은 공유하지 않는다.
        try (ConfigurableApplicationContext server2 = new SpringApplicationBuilder(ChatApplication.class)
                .profiles("mysql")
                .properties("server.port=0", "spring.datasource.url=" + environment.getRequiredProperty("spring.datasource.url"),
                        "spring.datasource.username=" + environment.getRequiredProperty("spring.datasource.username"),
                        "spring.datasource.password=" + environment.getRequiredProperty("spring.datasource.password"),
                        "spring.datasource.hikari.minimum-idle=0")
                .run()) {
            int port2 = server2.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            ChatHttp second = new ChatHttp(port2, json);
            MeterRegistry secondMeters = server2.getBean(MeterRegistry.class);
            try (WsTestClient tabA = WsTestClient.connect(port, a);
                 WsTestClient tabB = WsTestClient.connect(port2, b)) {
                double total1 = total(meters);
                double total2 = total(secondMeters);
                double failed1 = failed(meters);
                double failed2 = failed(secondMeters);
                long from1 = messageId(first.send(sender, room, "server1").body());
                long from2 = messageId(second.send(sender, room, "server2").body());
                List<Long> receivedA = received(tabA);
                List<Long> receivedB = received(tabB);
                JsonNode stored = json.readTree(first.get("/api/rooms/" + room + "/messages", a).body())
                        .at("/data/messages");
                List<Long> storedIds = new ArrayList<>();
                for (JsonNode message : stored) storedIds.add(message.path("id").asLong());
                double totalDelta1 = total(meters) - total1;
                double totalDelta2 = total(secondMeters) - total2;
                double failedDelta1 = failed(meters) - failed1;
                double failedDelta2 = failed(secondMeters) - failed2;
                ExperimentResults.record("cluster-f7-two-servers",
                        "from1,from2,receivedA,receivedB,storedIds,totalDelta1,totalDelta2,failedDelta1,failedDelta2",
                        String.join(",", Long.toString(from1), Long.toString(from2), csv(receivedA), csv(receivedB),
                                csv(storedIds), Double.toString(totalDelta1), Double.toString(totalDelta2),
                                Double.toString(failedDelta1), Double.toString(failedDelta2)));
                assertEquals(List.of(from1), receivedA);
                assertEquals(List.of(from2), receivedB);
                assertTrue(storedIds.containsAll(List.of(from1, from2)));
                assertEquals(1.0, totalDelta1);
                assertEquals(1.0, totalDelta2);
                assertEquals(0.0, failedDelta1);
                assertEquals(0.0, failedDelta2);
            }
        }
    }

    private long messageId(String body) {
        return json.readTree(body).at("/data/id").asLong();
    }

    private List<Long> received(WsTestClient client) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String frame; (frame = client.poll(Duration.ofSeconds(3))) != null; ) {
            ids.add(json.readTree(frame).at("/message/id").asLong());
        }
        return ids;
    }

    private static double total(MeterRegistry registry) {
        var timer = registry.find("chat.delivery.total").tag("transport", "rest").timer();
        return timer == null ? 0 : timer.count();
    }

    private static double failed(MeterRegistry registry) {
        var counter = registry.find("chat.delivery.failed").tag("transport", "rest").counter();
        return counter == null ? 0 : counter.count();
    }

    private static String csv(List<Long> ids) {
        return "\"" + ids + "\"";
    }
}
