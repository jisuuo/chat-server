package jissuo.chat.room.domain;

import java.time.Instant;

public record RoomCreatedEvent(long roomId, long userId, Instant at) {
}
