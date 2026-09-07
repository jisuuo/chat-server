package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class ChatRoomRepositoryTest {

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void savesAndLoadsChatRoom() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));

		ChatRoom found = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(found.getName()).isEqualTo("study");
		assertThat(found.isClosed()).isFalse();
	}

	@Test
	void activeMembershipUniquePerRoomAndUser() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.flush();

		ChatRoomMember duplicate = new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now());

		assertThatThrownBy(() -> {
			chatRoomMemberRepository.save(duplicate);
			chatRoomMemberRepository.flush();
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsNewActiveMembershipAfterPreviousOneLeft() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		ChatRoomMember first = chatRoomMemberRepository.save(
				new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		first.leave(OffsetDateTime.now());
		chatRoomMemberRepository.save(first);
		chatRoomMemberRepository.flush();

		ChatRoomMember rejoin = chatRoomMemberRepository.save(
				new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.flush();

		assertThat(rejoin.getId()).isNotEqualTo(first.getId());
	}
}
