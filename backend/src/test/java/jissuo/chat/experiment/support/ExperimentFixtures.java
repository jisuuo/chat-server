package jissuo.chat.experiment.support;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public final class ExperimentFixtures {
    private static final AtomicLong NAMES = new AtomicLong();
    private final JdbcClient jdbc;
    public ExperimentFixtures(JdbcClient jdbc) { this.jdbc = jdbc; }
    public long user(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:n, :t)")
                .param("n", nickname + NAMES.incrementAndGet())
                .param("t", LocalDateTime.now(ZoneOffset.UTC)).update(key, "id");
        return key.getKey().longValue();
    }
    public List<Long> messageIds(long roomId) {
        return jdbc.sql("SELECT id FROM messages WHERE room_id = :r ORDER BY id")
                .param("r", roomId).query(Long.class).list();
    }
    public long count(String sql, long roomId) {
        return jdbc.sql(sql).param("r", roomId).query(Long.class).single();
    }
}
