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
		Instant instant = sortAt.toInstant();
		String raw = instant.getEpochSecond() + ":" + instant.getNano() + ":" + roomId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Cursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", 3);
			OffsetDateTime sortAt = Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])).atOffset(ZoneOffset.UTC);
			Long roomId = Long.parseLong(parts[2]);
			return new Cursor(sortAt, roomId);
		} catch (RuntimeException e) {
			throw new InvalidCursorException(cursor);
		}
	}
}
