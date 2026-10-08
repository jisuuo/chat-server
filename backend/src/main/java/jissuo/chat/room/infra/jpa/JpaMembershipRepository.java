package jissuo.chat.room.infra.jpa;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.SQLException;
import java.util.Optional;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.room.domain.JoinBoundary;
import jissuo.chat.room.domain.Membership;
import jissuo.chat.room.domain.MembershipRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
public class JpaMembershipRepository implements MembershipRepository {

    private final SpringDataMembershipRepository memberships;

    public JpaMembershipRepository(SpringDataMembershipRepository memberships) {
        this.memberships = memberships;
    }

    @Override
    public void save(Membership membership) {
        try {
            // F34: 할당한 복합 키도 새 행으로 INSERT하고, 제약 위반을 서비스 트랜잭션 안에서 확인한다.
            memberships.saveAndFlush(toEntity(membership));
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKey(e)) {
                throw new ChatException(ErrorCode.ALREADY_MEMBER);
            }
            throw new ChatException(ErrorCode.UNAUTHENTICATED);
        }
    }

    @Override
    public Optional<Membership> find(long roomId, long userId) {
        return memberships.findById(new MembershipId(roomId, userId)).map(JpaMembershipRepository::toMembership);
    }

    @Override
    public boolean delete(long roomId, long userId) {
        return memberships.deleteByKey(roomId, userId) > 0;
    }

    private static MembershipEntity toEntity(Membership membership) {
        return new MembershipEntity(new MembershipId(membership.roomId(), membership.userId()),
                membership.boundary().messageId(),
                LocalDateTime.ofInstant(membership.boundary().joinedAt(), ZoneOffset.UTC));
    }

    private static Membership toMembership(MembershipEntity e) {
        return new Membership(e.getId().getRoomId(), e.getId().getUserId(),
                new JoinBoundary(e.getJoinedMessageId(), e.getJoinedAt().toInstant(ZoneOffset.UTC)));
    }

    private static boolean isDuplicateKey(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("23505".equals(sql.getSQLState()) || sql.getErrorCode() == 1062)) {
                return true;
            }
        }
        return false;
    }
}
