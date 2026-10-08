package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** F5: ping/pong이 없으면 조용히 끊긴 연결을 서버가 알아채지 못해 세션이 남는지 본다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class HalfOpenExperiment {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void 조용히_끊긴_연결은_세션_게이지에서_줄지_않는다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long zombie = fixtures.user("zombie");
        long room = http.createRoom(sender, "half-open");
        http.join(zombie, room);
        double before = gauge();

        try (SilentDropProxy proxy = new SilentDropProxy(port)) {
            WsTestClient client = WsTestClient.connect(proxy.port(), zombie);
            Thread.sleep(500);
            proxy.freeze();
            // 클라이언트 쪽은 닫았지만 프록시가 서버로 전달하지 않는다
            client.close();
            long frozenAt = System.nanoTime();
            for (int seconds : new int[] {0, 10, 30, 60}) {
                long wait = TimeUnit.SECONDS.toMillis(seconds) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - frozenAt);
                Thread.sleep(Math.max(0, wait));
                ExperimentResults.record("ws-half-open", "phase,elapsedSeconds,extraSessions",
                        "idle," + seconds + "," + (gauge() - before));
            }
            // 남은 세션으로 push하면 프록시 버퍼가 찰 때까지는 성공하고 그 뒤로는 막힌다(F4와 연결)
            for (int i = 0; i < 30; i++) {
                long started = System.nanoTime();
                HttpResponse<String> response = http.send(sender, room, "가".repeat(1000));
                ExperimentResults.record("ws-half-open-push", "index,status,restMs,extraSessions",
                        i + "," + response.statusCode() + ","
                                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "," + (gauge() - before));
            }
        }
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }
}
