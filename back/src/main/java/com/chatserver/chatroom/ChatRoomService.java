package com.chatserver.chatroom;

import java.time.OffsetDateTime;

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
}
