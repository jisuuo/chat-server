package jissuo.chat.room.application;

import java.util.List;
import jissuo.chat.room.domain.Room;

public record RoomPage(List<Room> rooms, boolean hasMore, String nextCursor) {
}
