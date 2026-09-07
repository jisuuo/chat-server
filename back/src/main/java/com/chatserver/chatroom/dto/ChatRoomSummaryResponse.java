package com.chatserver.chatroom.dto;

import java.time.OffsetDateTime;

public record ChatRoomSummaryResponse(
		Long roomId,
		String name,
		String lastMessage,
		OffsetDateTime lastMessageAt
) {
}
