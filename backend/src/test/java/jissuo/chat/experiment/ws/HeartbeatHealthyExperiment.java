package jissuo.chat.experiment.ws;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import jissuo.chat.experiment.support.ExperimentFixtures;
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

/** 정상 클라이언트의 자동 pong이 서버의 유휴 연결을 유지하는지 확인한다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class HeartbeatHealthyExperiment {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void 정상_연결은_30초가_지나도_유지된다() throws Exception {
        long user = new ExperimentFixtures(jdbc).user("healthy");
        double before = meters.get("chat.ws.sessions").gauge().value();
        try (WsTestClient client = WsTestClient.connect(port, user)) {
            Thread.sleep(40_000);
            assertThat(client.isOpen()).isTrue();
            assertThat(meters.get("chat.ws.sessions").gauge().value() - before).isEqualTo(1);
        }
    }
}
