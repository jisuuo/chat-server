package jissuo.chat.room.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RoomRepository {

    long save(RoomName name, long createdBy, Instant createdAt);

    Optional<Room> findById(long id);

    /**
     * 최근 메시지순으로, 메시지가 없는 방은 뒤에 생성 역순으로 돌려준다 (ADR-016).
     *
     * @param cursor null이면 처음부터
     */
    List<Room> findPage(RoomListCursor cursor, int limit);

    /** 지금 값보다 큰 번호일 때만 바꾼다 (ADR-016). */
    void advanceLastMessageId(long roomId, long messageId);
}
