package jissuo.chat.room.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.room.domain.JoinBoundary;
import jissuo.chat.room.domain.Membership;
import jissuo.chat.room.domain.RoomName;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * ADR-025: JDBC와 JPA 구현체가 같은 계약을 지키는지 보려고 인터페이스로 주입한다.
 */
public abstract class MembershipRepositoryContract {

    static final Instant AT = Instant.parse("2026-10-07T01:02:03.123456Z");

    @Autowired
    MembershipRepository repository;

    @Autowired
    RoomRepository rooms;

    @Autowired
    JdbcClient jdbc;

    long roomId;
    long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser();
        roomId = rooms.save(new RoomName("방"), userId, AT);
    }

    @Test
    void 저장한_멤버십을_찾으면_경계_번호와_시각이_그대로다() {
        Membership membership = new Membership(roomId, userId, new JoinBoundary(120, AT));

        repository.save(membership);

        assertThat(repository.find(roomId, userId)).contains(membership);
    }

    @Test
    void 멤버가_아니면_비어_있다() {
        assertThat(repository.find(roomId, userId)).isEmpty();
    }

    @Test
    void 같은_방에_두_번_저장하면_이미_멤버다() {
        // R2, ADR-019: 중복 입장은 DB의 PK (room_id, user_id)가 막는다
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));

        assertThatThrownBy(() -> repository.save(new Membership(roomId, userId, new JoinBoundary(5, AT))))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ALREADY_MEMBER));
        assertThat(repository.find(roomId, userId).orElseThrow().boundary().messageId()).isZero();
    }

    @Test
    void 없는_사용자를_저장하면_인증_실패다() {
        // ADR-031: 인증은 형식만 보므로 없는 사용자는 FK 위반으로 처음 드러난다
        assertThatThrownBy(() -> repository.save(new Membership(roomId, Long.MAX_VALUE, new JoinBoundary(0, AT))))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void 삭제하면_true를_돌려주고_더는_멤버가_아니다() {
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));

        assertThat(repository.delete(roomId, userId)).isTrue();
        assertThat(repository.find(roomId, userId)).isEmpty();
    }

    @Test
    void 멤버가_아닌데_삭제하면_false다() {
        assertThat(repository.delete(roomId, userId)).isFalse();
    }

    @Test
    void 나간_뒤_다시_저장하면_새_경계를_가진다() {
        // ADR-010: 나가기는 행 삭제이고 재입장은 새 행이다
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));
        repository.delete(roomId, userId);
        Membership rejoined = new Membership(roomId, userId, new JoinBoundary(42, AT.plusSeconds(60)));

        repository.save(rejoined);

        assertThat(repository.find(roomId, userId)).contains(rejoined);
    }

    @Test
    void 삭제는_그_사용자의_그_방_멤버십만_지운다() {
        long otherUser = insertUser();
        long otherRoom = rooms.save(new RoomName("다른 방"), userId, AT);
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));
        repository.save(new Membership(roomId, otherUser, new JoinBoundary(0, AT)));
        repository.save(new Membership(otherRoom, userId, new JoinBoundary(0, AT)));

        repository.delete(roomId, userId);

        assertThat(repository.find(roomId, otherUser)).isPresent();
        assertThat(repository.find(otherRoom, userId)).isPresent();
    }

    // room은 user 패키지를 모르므로(의존 방향 규칙) 테스트도 SQL로 사용자를 만든다
    private long insertUser() {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES ('철수', :at)")
                .param("at", LocalDateTime.ofInstant(AT, ZoneOffset.UTC))
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
