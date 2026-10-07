package jissuo.chat.experiment.timecursor;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** F2에서 채택하지 않은 시각 커서를 테스트 안에서만 재현한다 (ADR-088). */
final class TimeCursorPoller {
    private record Row(long id, LocalDateTime createdAt) {}
    private final JdbcClient jdbc;
    private final long roomId;
    private final boolean inclusive;
    private final int limit;
    private LocalDateTime cursor = LocalDateTime.of(1970, 1, 1, 0, 0);
    TimeCursorPoller(JdbcClient jdbc, long roomId, boolean inclusive, int limit) {
        this.jdbc = jdbc; this.roomId = roomId; this.inclusive = inclusive; this.limit = limit;
    }
    List<Long> poll() {
        List<Row> rows = jdbc.sql("SELECT id, created_at FROM messages WHERE room_id = :r AND created_at "
                        + (inclusive ? ">=" : ">") + " :t ORDER BY created_at, id LIMIT :n")
                .param("r", roomId).param("t", cursor).param("n", limit)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getObject(2, LocalDateTime.class))).list();
        if (!rows.isEmpty()) cursor = rows.getLast().createdAt();
        return rows.stream().map(Row::id).toList();
    }
}
