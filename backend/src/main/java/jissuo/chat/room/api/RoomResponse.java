package jissuo.chat.room.api;

import java.time.Instant;
import jissuo.chat.room.domain.Room;

public record RoomResponse(long id, String name, long createdBy, Long lastMessageId, Instant createdAt) {

    static RoomResponse from(Room room) {
        return new RoomResponse(room.id(), room.name().value(), room.createdBy(),
                room.lastMessageId(), room.createdAt());
    }
}
