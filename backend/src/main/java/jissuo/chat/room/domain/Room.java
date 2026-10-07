package jissuo.chat.room.domain;

import java.time.Instant;

/**
 * @param lastMessageId 방 목록 정렬용 (R7, ADR-016). 메시지가 없으면 null
 */
public record Room(long id, RoomName name, long createdBy, Long lastMessageId, Instant createdAt) {
}
