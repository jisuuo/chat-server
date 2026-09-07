package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class InvalidCursorException extends ApplicationException {

	public InvalidCursorException(String cursor) {
		super("INVALID_CURSOR", "Invalid cursor: " + cursor);
	}
}
