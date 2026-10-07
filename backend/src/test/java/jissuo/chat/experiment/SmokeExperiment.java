package jissuo.chat.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import jissuo.chat.experiment.support.Concurrently;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.room.application.RoomService;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("experiment")
@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64"})
@ActiveProfiles("mysql")
class SmokeExperiment {
    @Autowired JdbcClient jdbc;
    @Autowired RoomService rooms;
    @Autowired MessageService messages;
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
    @Test void concurrentSends() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        long user = fixtures.user("smoke");
        long room = rooms.create(user, "smoke").id();
        var sent = new AtomicInteger();
        var errors = Concurrently.run(4, Duration.ofSeconds(1), () -> {
            messages.send(user, room, "hello"); sent.incrementAndGet();
        });
        ExperimentResults.record("smoke", "sent", Integer.toString(sent.get()));
        assertThat(errors).isEmpty();
        assertThat(fixtures.messageIds(room)).hasSize(sent.get());
    }
}
