package jissuo.chat.room.infra.jpa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import jissuo.chat.room.domain.Room;
import jissuo.chat.room.domain.RoomListCursor;
import jissuo.chat.room.domain.RoomName;
import jissuo.chat.room.domain.RoomRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
public class JpaRoomRepository implements RoomRepository {

    private final SpringDataRoomRepository rooms;

    public JpaRoomRepository(SpringDataRoomRepository rooms) {
        this.rooms = rooms;
    }

    @Override
    public long save(RoomName name, long createdBy, Instant createdAt) {
        return rooms.save(new RoomEntity(name.value(), createdBy,
                LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))).getId();
    }

    @Override
    public Optional<Room> findById(long id) {
        return rooms.findById(id).map(JpaRoomRepository::toRoom);
    }

    @Override
    public List<Room> findPage(RoomListCursor cursor, int limit) {
        Pageable page = PageRequest.ofSize(limit);
        List<RoomEntity> found;
        if (cursor == null) {
            found = rooms.findFirstPage(page);
        } else if (cursor.lastMessageId() == null) {
            found = rooms.findEmptyRoomsAfter(cursor.id(), page);
        } else {
            found = rooms.findPageAfter(cursor.lastMessageId(), cursor.id(), page);
        }
        return found.stream().map(JpaRoomRepository::toRoom).toList();
    }

    @Override
    public void advanceLastMessageId(long roomId, long messageId) {
        rooms.advanceLastMessageId(roomId, messageId);
    }

    private static Room toRoom(RoomEntity e) {
        return new Room(e.getId(), new RoomName(e.getName()), e.getCreatedBy(),
                e.getLastMessageId(), e.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
