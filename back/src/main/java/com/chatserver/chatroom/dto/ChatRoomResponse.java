package com.chatserver.chatroom.dto;

import java.time.OffsetDateTime;

public record ChatRoomResponse(
		Long roomId,
		String name,
		OffsetDateTime createdAt
) {
}
