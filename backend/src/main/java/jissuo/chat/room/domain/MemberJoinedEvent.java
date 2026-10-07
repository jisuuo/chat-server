package jissuo.chat.room.domain;

import java.time.Instant;

public record MemberJoinedEvent(long roomId, long userId, Instant at) {
}
