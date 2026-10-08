package jissuo.chat.room.infra.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.room.domain.JoinBoundary;
import jissuo.chat.room.domain.Membership;
import jissuo.chat.room.domain.MembershipRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jdbc", matchIfMissing = true)
public class JdbcMembershipRepository implements MembershipRepository {

    private final JdbcClient jdbc;

    public JdbcMembershipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Membership membership) {
        try {
            jdbc.sql("""
                            INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
                            VALUES (:roomId, :userId, :joinedMessageId, :joinedAt)
                            """)
                    .param("roomId", membership.roomId())
                    .param("userId", membership.userId())
                    .param("joinedMessageId", membership.boundary().messageId())
                    .param("joinedAt", LocalDateTime.ofInstant(membership.boundary().joinedAt(), ZoneOffset.UTC))
                    .update();
        } catch (DuplicateKeyException e) {
            // R2, ADR-019: 동시에 들어와도 PK (room_id, user_id)가 하나만 남긴다
            throw new ChatException(ErrorCode.ALREADY_MEMBER);
        } catch (DataIntegrityViolationException e) {
            // ADR-031: 서비스가 방 존재를 먼저 확인하고 방은 지우지 않으므로(ADR-012), 남는 FK 위반은 없는 사용자다.
            // 확인과 저장 사이에 방이 사라지는 경우(F19)도 여기서 401로 보인다
            throw new ChatException(ErrorCode.UNAUTHENTICATED);
        }
    }

    @Override
    public Optional<Membership> find(long roomId, long userId) {
        return jdbc.sql("""
                        SELECT room_id, user_id, joined_message_id, joined_at FROM room_members
                        WHERE room_id = :roomId AND user_id = :userId
                        """)
                .param("roomId", roomId)
                .param("userId", userId)
                .query(JdbcMembershipRepository::toMembership)
                .optional();
    }

    @Override
    public List<Long> findUserIds(long roomId) {
        // PK (room_id, user_id)의 앞부분으로 찾는다
        return jdbc.sql("SELECT user_id FROM room_members WHERE room_id = :roomId ORDER BY user_id")
                .param("roomId", roomId)
                .query(Long.class)
                .list();
    }

    @Override
    public boolean delete(long roomId, long userId) {
        // ADR-010: 나가기는 행 삭제다. 지운 행 수로 멤버였는지 판단해 따로 조회하지 않는다
        return jdbc.sql("DELETE FROM room_members WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId)
                .param("userId", userId)
                .update() > 0;
    }

    private static Membership toMembership(ResultSet rs, int rowNum) throws SQLException {
        return new Membership(
                rs.getLong("room_id"),
                rs.getLong("user_id"),
                new JoinBoundary(
                        rs.getLong("joined_message_id"),
                        rs.getObject("joined_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
    }
}
