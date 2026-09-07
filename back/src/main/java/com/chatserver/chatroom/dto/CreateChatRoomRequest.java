package com.chatserver.chatroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChatRoomRequest(
		@NotBlank @Size(max = 100) String name
) {
}
