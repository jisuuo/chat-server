package jissuo.chat.room.infra.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import jissuo.chat.room.domain.Room;
import jissuo.chat.room.domain.RoomListCursor;
import jissuo.chat.room.domain.RoomName;
import jissuo.chat.room.domain.RoomRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jdbc", matchIfMissing = true)
public class JdbcRoomRepository implements RoomRepository {

    private static final String COLUMNS = "SELECT id, name, created_by, last_message_id, created_at FROM rooms ";

    // 계획 1 세부 6: DESC에서 NULL이 PostgreSQL은 앞, MySQL은 뒤에 오므로 NULL 위치를 직접 정해 두 DB를 맞춘다.
    // rooms에는 이 정렬용 인덱스를 두지 않는다 (F20에서 측정)
    private static final String ORDER = "ORDER BY (last_message_id IS NULL), last_message_id DESC, id DESC LIMIT :limit";

    private final JdbcClient jdbc;

    public JdbcRoomRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long save(RoomName name, long createdBy, Instant createdAt) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO rooms (name, created_by, created_at) VALUES (:name, :createdBy, :createdAt)")
                .param("name", name.value())
                .param("createdBy", createdBy)
                .param("createdAt", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<Room> findById(long id) {
        return jdbc.sql(COLUMNS + "WHERE id = :id")
                .param("id", id)
                .query(JdbcRoomRepository::toRoom)
                .optional();
    }

    @Override
    public List<Room> findPage(RoomListCursor cursor, int limit) {
        if (cursor == null) {
            return jdbc.sql(COLUMNS + ORDER).param("limit", limit).query(JdbcRoomRepository::toRoom).list();
        }
        if (cursor.lastMessageId() == null) {
            // 메시지 없는 방 구간에 들어왔으면 그 뒤에는 메시지 없는 방만 남는다
            return jdbc.sql(COLUMNS + "WHERE last_message_id IS NULL AND id < :id " + ORDER)
                    .param("id", cursor.id())
                    .param("limit", limit)
                    .query(JdbcRoomRepository::toRoom)
                    .list();
        }
        return jdbc.sql(COLUMNS + """
                        WHERE last_message_id < :lastMessageId
                           OR (last_message_id = :lastMessageId AND id < :id)
                           OR last_message_id IS NULL
                        """ + ORDER)
                .param("lastMessageId", cursor.lastMessageId())
                .param("id", cursor.id())
                .param("limit", limit)
                .query(JdbcRoomRepository::toRoom)
                .list();
    }

    @Override
    public void advanceLastMessageId(long roomId, long messageId) {
        // ADR-016: 늦게 커밋된 작은 번호가 이미 들어간 큰 번호를 덮어쓰지 않게 한다. 갱신 경합은 F20에서 측정한다
        jdbc.sql("""
                        UPDATE rooms SET last_message_id = :messageId
                        WHERE id = :roomId AND (last_message_id IS NULL OR last_message_id < :messageId)
                        """)
                .param("messageId", messageId)
                .param("roomId", roomId)
                .update();
    }

    private static Room toRoom(ResultSet rs, int rowNum) throws SQLException {
        return new Room(
                rs.getLong("id"),
                new RoomName(rs.getString("name")),
                rs.getLong("created_by"),
                rs.getObject("last_message_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
