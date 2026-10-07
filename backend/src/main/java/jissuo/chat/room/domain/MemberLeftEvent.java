package jissuo.chat.room.domain;

import java.time.Instant;

public record MemberLeftEvent(long roomId, long userId, Instant at) {
}
