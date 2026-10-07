package jissuo.chat.message.infra.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.JoinBoundary;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=B", "chat.join-boundary=time"})
@ActiveProfiles("mysql")
class TimeBoundaryConfigurationTest {

    @Autowired MessageRepository repository;
    @Autowired JdbcClient jdbc;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Test
    void 설정한_시각_경계와_B_테이블을_함께_사용한다() {
        jdbc.sql("DELETE FROM messages_b").update();
        Instant joinedAt = Instant.parse("2026-10-07T01:02:03.123456Z");
        repository.save(91, 92, new MessageContent("동시"), joinedAt);
        long laterId = repository.save(91, 92, new MessageContent("이후"), joinedAt.plusSeconds(1)).id();

        assertThat(repository.find(91, new JoinBoundary(0, joinedAt), MessageCursor.latest(), 10))
                .extracting(message -> message.id()).containsExactly(laterId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM messages_b WHERE room_id = 91")
                .query(Long.class).single()).isEqualTo(2);
    }
}
