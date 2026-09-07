package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class ChatRoomNotFoundException extends ApplicationException {

	public ChatRoomNotFoundException(Long roomId) {
		super("CHAT_ROOM_NOT_FOUND", "Chat room not found: " + roomId);
	}
}
