package com.chatserver.chatroom;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.chatserver.chatroom.dto.ChatRoomListResponse;
import com.chatserver.chatroom.dto.ChatRoomSummaryResponse;
import com.chatserver.chatroom.exception.ChatRoomClosedException;
import com.chatserver.chatroom.exception.ChatRoomNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatRoomMemberRepository chatRoomMemberRepository;

	public ChatRoomService(ChatRoomRepository chatRoomRepository,
			ChatRoomMemberRepository chatRoomMemberRepository) {
		this.chatRoomRepository = chatRoomRepository;
		this.chatRoomMemberRepository = chatRoomMemberRepository;
	}

	@Transactional
	public ChatRoom createChatRoom(String name, String creatorUserId) {
		OffsetDateTime now = OffsetDateTime.now();
		ChatRoom room = chatRoomRepository.save(new ChatRoom(name, creatorUserId, now));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), creatorUserId, now));
		return room;
	}

	@Transactional
	public void join(Long roomId, String userId) {
		ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
				.orElseThrow(() -> new ChatRoomNotFoundException(roomId));
		if (room.isClosed()) {
			throw new ChatRoomClosedException(roomId);
		}
		boolean alreadyActive = chatRoomMemberRepository
				.findByChatRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
				.isPresent();
		if (alreadyActive) {
			return;
		}
		chatRoomMemberRepository.save(new ChatRoomMember(roomId, userId, OffsetDateTime.now()));
	}

	@Transactional
	public void leave(Long roomId, String userId) {
		ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
				.orElseThrow(() -> new ChatRoomNotFoundException(roomId));
		Optional<ChatRoomMember> active = chatRoomMemberRepository
				.findByChatRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);
		if (active.isEmpty()) {
			return;
		}
		active.get().leave(OffsetDateTime.now());
		chatRoomMemberRepository.save(active.get());

		long remaining = chatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(roomId);
		if (remaining == 0) {
			room.close(OffsetDateTime.now());
			chatRoomRepository.save(room);
		}
	}

	@Transactional(readOnly = true)
	public ChatRoomListResponse listMyRooms(String userId, String cursor, int limit) {
		int limitPlusOne = limit + 1;
		List<ChatRoomSummaryProjection> rows = (cursor == null)
				? chatRoomRepository.findFirstPage(userId, limitPlusOne)
				: queryNextPage(userId, cursor, limitPlusOne);

		boolean hasMore = rows.size() > limit;
		List<ChatRoomSummaryProjection> page = hasMore ? rows.subList(0, limit) : rows;

		String nextCursor = hasMore
				? ChatRoomListCursor.encode(
						toOffsetDateTime(page.get(page.size() - 1).getSortAt()),
						page.get(page.size() - 1).getId())
				: null;

		List<ChatRoomSummaryResponse> items = page.stream()
				.map(r -> new ChatRoomSummaryResponse(
						r.getId(),
						r.getName(),
						null,
						toOffsetDateTime(r.getLastMessageAt())))
				.toList();

		return new ChatRoomListResponse(items, nextCursor);
	}

	private List<ChatRoomSummaryProjection> queryNextPage(String userId, String cursor, int limitPlusOne) {
		ChatRoomListCursor.Cursor decoded = ChatRoomListCursor.decode(cursor);
		return chatRoomRepository.findNextPage(userId, decoded.sortAt(), decoded.roomId(), limitPlusOne);
	}

	private OffsetDateTime toOffsetDateTime(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}
}
