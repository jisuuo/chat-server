package com.chatserver.chatroom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import com.chatserver.chatroom.exception.InvalidCursorException;

public final class ChatRoomListCursor {

	public record Cursor(OffsetDateTime sortAt, Long roomId) {
	}

	private ChatRoomListCursor() {
	}

	public static String encode(OffsetDateTime sortAt, Long roomId) {
		String raw = sortAt.toInstant().toEpochMilli() + ":" + roomId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Cursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", 2);
			OffsetDateTime sortAt = Instant.ofEpochMilli(Long.parseLong(parts[0])).atOffset(ZoneOffset.UTC);
			Long roomId = Long.parseLong(parts[1]);
			return new Cursor(sortAt, roomId);
		} catch (RuntimeException e) {
			throw new InvalidCursorException(cursor);
		}
	}
}
