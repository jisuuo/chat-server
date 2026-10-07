package jissuo.chat.message.infra.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.JoinBoundary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

public class JdbcMessageRepository implements MessageRepository {

    private final JdbcClient jdbc;
    private final String table;
    private final JoinBoundaryMode boundaryMode;

    public JdbcMessageRepository(JdbcClient jdbc, String table, JoinBoundaryMode boundaryMode) {
        if (!table.equals("messages") && !table.equals("messages_b")) {
            throw new IllegalArgumentException("허용되지 않은 메시지 테이블: " + table);
        }
        this.jdbc = jdbc;
        this.table = table;
        this.boundaryMode = boundaryMode;
    }

    @Override
    public Message save(long roomId, long senderId, MessageContent content, Instant createdAt) {
        var keyHolder = new GeneratedKeyHolder();
        // 두 DB의 created_at은 마이크로초 정밀도다. 반환값도 저장된 값과 맞춘다.
        Instant storedAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        // 두 스키마의 SQL은 테이블 이름만 다르다. PostgreSQL은 반환 키 컬럼을 지정해야 한다.
        jdbc.sql("INSERT INTO " + table + " (room_id, sender_id, content, created_at) "
                        + "VALUES (:roomId, :senderId, :content, :createdAt)")
                .param("roomId", roomId)
                .param("senderId", senderId)
                .param("content", content.value())
                .param("createdAt", LocalDateTime.ofInstant(storedAt, ZoneOffset.UTC))
                .update(keyHolder, "id");
        return new Message(keyHolder.getKey().longValue(), roomId, senderId, content, storedAt);
    }

    @Override
    public List<Message> find(long roomId, JoinBoundary boundary, MessageCursor cursor, int limit) {
        String boundaryColumn = boundaryMode == JoinBoundaryMode.ID ? "id" : "created_at";
        Object boundaryValue = boundaryMode == JoinBoundaryMode.ID
                ? boundary.messageId() : LocalDateTime.ofInstant(boundary.joinedAt(), ZoneOffset.UTC);
        String cursorCondition = switch (cursor.direction()) {
            case LATEST -> "";
            case AFTER -> " AND id > :cursorId";
            case BEFORE -> " AND id < :cursorId";
        };
        String order = cursor.direction() == MessageCursor.Direction.AFTER ? "ASC" : "DESC";
        var query = jdbc.sql("SELECT id, room_id, sender_id, content, created_at FROM " + table
                        + " WHERE room_id = :roomId AND " + boundaryColumn + " > :boundary"
                        + cursorCondition + " ORDER BY id " + order + " LIMIT :limit")
                .param("roomId", roomId)
                .param("boundary", boundaryValue)
                .param("limit", limit);
        if (cursor.id() != null) {
            query = query.param("cursorId", cursor.id());
        }
        List<Message> found = query.query(JdbcMessageRepository::toMessage).list();
        if (cursor.direction() == MessageCursor.Direction.AFTER) {
            return found;
        }
        // 최신·before는 최근 n건을 고른 뒤 응답 순서만 다시 오래된 순으로 만든다 (ADR-017).
        List<Message> ascending = new ArrayList<>(found);
        Collections.reverse(ascending);
        return ascending;
    }

    private static Message toMessage(ResultSet rs, int rowNum) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("room_id"), rs.getLong("sender_id"),
                new MessageContent(rs.getString("content")),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
