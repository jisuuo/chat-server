package jissuo.chat.message.infra.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.JoinBoundary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

abstract class JdbcMessageRepositoryContract {

    static final Instant AT = Instant.parse("2026-10-07T01:02:03.123456Z");

    @Autowired MessageRepository repository;
    @Autowired JdbcClient jdbc;

    abstract String tableName();

    @BeforeEach
    void clear() {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
    }

    @Test
    void 설정한_테이블에_저장하고_내용과_UTC_시각을_돌려준다() {
        Message saved = repository.save(11, 21, new MessageContent("😀".repeat(1000)), AT);

        assertThat(saved).isEqualTo(new Message(saved.id(), 11, 21,
                new MessageContent("😀".repeat(1000)), AT));
        assertThat(saved.id()).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + tableName()).query(Long.class).single()).isEqualTo(1);
        String other = tableName().equals("messages") ? "messages_b" : "messages";
        assertThat(jdbc.sql("SELECT COUNT(*) FROM " + other).query(Long.class).single()).isZero();
    }

    @Test
    void 나노초가_있는_저장_시각은_DB_정밀도로_맞춰_반환한다() {
        Instant withNanos = AT.plusNanos(789);
        Message saved = repository.save(11, 21, new MessageContent("시각"), withNanos);

        assertThat(saved.createdAt()).isEqualTo(AT);
        assertThat(repository.find(11, JoinBoundary.at(null, AT.minusSeconds(1)),
                MessageCursor.latest(), 1)).containsExactly(saved);
    }

    @Test
    void 최신과_before는_최근_것부터_고른_뒤_오래된_순으로_돌려준다() {
        long[] ids = seed();
        JoinBoundary boundary = JoinBoundary.at(null, AT.minusSeconds(1));

        assertThat(ids(repository.find(11, boundary, MessageCursor.latest(), 2)))
                .containsExactly(ids[2], ids[3]);
        assertThat(ids(repository.find(11, boundary, MessageCursor.before(ids[3]), 2)))
                .containsExactly(ids[1], ids[2]);
        assertThat(ids(repository.find(11, boundary, MessageCursor.before(ids[0]), 2))).isEmpty();
    }

    @Test
    void after는_이후_것부터_오래된_순으로_돌려준다() {
        long[] ids = seed();
        JoinBoundary boundary = JoinBoundary.at(null, AT.minusSeconds(1));

        assertThat(ids(repository.find(11, boundary, MessageCursor.after(ids[0]), 2)))
                .containsExactly(ids[1], ids[2]);
        assertThat(ids(repository.find(11, boundary, MessageCursor.after(ids[3]), 2))).isEmpty();
    }

    @Test
    void id_경계는_입장_당시_번호까지_제외한다() {
        long[] ids = seed();
        JoinBoundary boundary = new JoinBoundary(ids[1], AT.minusSeconds(100));

        assertThat(ids(repository.find(11, boundary, MessageCursor.latest(), 10)))
                .containsExactly(ids[2], ids[3]);
        assertThat(ids(repository.find(11, boundary, MessageCursor.before(ids[2]), 10))).isEmpty();
        assertThat(ids(repository.find(11, boundary, MessageCursor.after(0), 10)))
                .containsExactly(ids[2], ids[3]);
    }

    @Test
    void time_경계는_같은_시각까지_제외하고_번호_경계와_독립적이다() {
        MessageRepository timeRepository = new JdbcMessageRepository(jdbc, tableName(), JoinBoundaryMode.TIME);
        Message first = timeRepository.save(11, 21, new MessageContent("이전"), AT.minusSeconds(1));
        timeRepository.save(11, 21, new MessageContent("동시"), AT);
        Message later = timeRepository.save(11, 21, new MessageContent("이후"), AT.plusSeconds(1));
        JoinBoundary boundary = new JoinBoundary(first.id(), AT);

        assertThat(ids(timeRepository.find(11, boundary, MessageCursor.latest(), 10)))
                .containsExactly(later.id());
        assertThat(ids(timeRepository.find(11, boundary, MessageCursor.after(0), 10)))
                .containsExactly(later.id());
    }

    @Test
    void 다른_방의_메시지는_섞이지_않는다() {
        Message one = repository.save(11, 21, new MessageContent("첫 방"), AT);
        repository.save(12, 22, new MessageContent("다른 방"), AT);
        Message two = repository.save(11, 21, new MessageContent("첫 방 두번째"), AT);
        JoinBoundary boundary = JoinBoundary.at(null, AT.minusSeconds(1));

        assertThat(ids(repository.find(11, boundary, MessageCursor.latest(), 10)))
                .containsExactly(one.id(), two.id());
    }

    private long[] seed() {
        long[] ids = new long[4];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = repository.save(11, 21, new MessageContent("메시지 " + i), AT.plusSeconds(i)).id();
        }
        return ids;
    }

    private static List<Long> ids(List<Message> messages) {
        return messages.stream().map(Message::id).toList();
    }
}
