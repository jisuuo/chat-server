package jissuo.chat.message.domain;

import java.time.Instant;

public record Message(long id, long roomId, long senderId, MessageContent content, Instant createdAt) {
}
