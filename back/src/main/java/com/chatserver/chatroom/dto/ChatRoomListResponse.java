package com.chatserver.chatroom.dto;

import java.util.List;

public record ChatRoomListResponse(
		List<ChatRoomSummaryResponse> items,
		String nextCursor
) {
}
