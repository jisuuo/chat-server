package jissuo.chat.room.infra.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

interface SpringDataMembershipRepository extends JpaRepository<MembershipEntity, MembershipId> {

    // ADR-010: 삭제한 행 수로 기존 멤버 여부를 판단한다.
    @Transactional
    @Modifying
    @Query("DELETE FROM MembershipEntity m WHERE m.id.roomId = :roomId AND m.id.userId = :userId")
    int deleteByKey(long roomId, long userId);
}
