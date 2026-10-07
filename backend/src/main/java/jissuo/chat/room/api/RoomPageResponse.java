package jissuo.chat.room.api;

import java.util.List;
import jissuo.chat.room.application.RoomPage;

public record RoomPageResponse(List<RoomResponse> rooms, boolean hasMore, String nextCursor) {

    static RoomPageResponse from(RoomPage page) {
        return new RoomPageResponse(page.rooms().stream().map(RoomResponse::from).toList(),
                page.hasMore(), page.nextCursor());
    }
}
