package com.chatserver.chatroom;

import java.time.Instant;

public interface ChatRoomSummaryProjection {

	Long getId();

	String getName();

	Instant getLastMessageAt();

	Instant getSortAt();
}
