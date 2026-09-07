package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class ChatRoomClosedException extends ApplicationException {

	public ChatRoomClosedException(Long roomId) {
		super("CHAT_ROOM_CLOSED", "Chat room is closed: " + roomId);
	}
}
