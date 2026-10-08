package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F6: 끊겨 있던 동안 보낸 메시지를 다시 연결한 뒤에도 받지 못하는지 본다 (따라잡기 없음, 계획 7 세부 8). */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class ReconnectLossExperiment {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    void 끊긴_동안_보낸_메시지는_다시_연결해도_push로_오지_않는다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long member = fixtures.user("member");
        long room = http.createRoom(sender, "reconnect");
        http.join(member, room);
        double before = gauge();

        WsTestClient first = WsTestClient.connect(port, member);
        Thread.sleep(500);
        first.close();
        Thread.sleep(500);
        long missed = idOf(http.send(sender, room, "끊긴 동안").body());
        WsTestClient second = WsTestClient.connect(port, member);
        Thread.sleep(500);
        long after = idOf(http.send(sender, room, "다시 연결한 뒤").body());

        List<Long> received = new ArrayList<>();
        for (String frame; (frame = second.poll(Duration.ofSeconds(3))) != null; ) {
            received.add(json.readTree(frame).at("/message/id").asLong());
        }
        second.close();
        JsonNode stored = json.readTree(http.get("/api/rooms/" + room + "/messages", member).body()).at("/data/messages");
        boolean missedStored = false;
        for (JsonNode message : stored) {
            missedStored |= message.get("id").asLong() == missed;
        }
        ExperimentResults.record("ws-reconnect-loss",
                "missedId,afterId,receivedIds,missedReceived,afterReceived,missedStored,extraSessions",
                String.join(",", Long.toString(missed), Long.toString(after), received.toString().replace(',', ' '),
                        Boolean.toString(received.contains(missed)), Boolean.toString(received.contains(after)),
                        Boolean.toString(missedStored), Double.toString(gauge() - before)));
    }

    private long idOf(String body) {
        return json.readTree(body).at("/data/id").asLong();
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }
}
