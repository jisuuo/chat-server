package jissuo.chat.room.infra.jpa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

interface SpringDataRoomRepository extends JpaRepository<RoomEntity, Long> {

    // ADR-016: 두 DB의 NULL 정렬을 맞추고, 목록은 메시지 번호와 방 번호로 정렬한다.
    String ORDER = " ORDER BY CASE WHEN r.lastMessageId IS NULL THEN 1 ELSE 0 END, r.lastMessageId DESC, r.id DESC";

    @Query("SELECT r FROM RoomEntity r" + ORDER)
    List<RoomEntity> findFirstPage(Pageable page);

    @Query("SELECT r FROM RoomEntity r WHERE r.lastMessageId IS NULL AND r.id < :id" + ORDER)
    List<RoomEntity> findEmptyRoomsAfter(long id, Pageable page);

    @Query("SELECT r FROM RoomEntity r WHERE r.lastMessageId < :lastMessageId"
            + " OR (r.lastMessageId = :lastMessageId AND r.id < :id) OR r.lastMessageId IS NULL" + ORDER)
    List<RoomEntity> findPageAfter(long lastMessageId, long id, Pageable page);

    // ADR-016: 늦게 커밋된 작은 번호가 큰 번호를 덮지 못하도록 조건부 UPDATE를 유지한다.
    @Transactional
    @Modifying
    @Query("UPDATE RoomEntity r SET r.lastMessageId = :messageId"
            + " WHERE r.id = :roomId AND (r.lastMessageId IS NULL OR r.lastMessageId < :messageId)")
    int advanceLastMessageId(long roomId, long messageId);
}
