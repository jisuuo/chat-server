package com.chatserver.chatroom;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

	Optional<ChatRoomMember> findByChatRoomIdAndUserIdAndLeftAtIsNull(Long chatRoomId, String userId);
}
