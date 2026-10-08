package jissuo.chat.message.api;

import java.time.Instant;
import jissuo.chat.message.domain.Message;

public record MessageResponse(long id, long roomId, long senderId, String content, Instant createdAt) {

    public static MessageResponse from(Message message) {
        return new MessageResponse(message.id(), message.roomId(), message.senderId(),
                message.content().value(), message.createdAt());
    }
}
