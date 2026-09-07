package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ChatRoomServiceCreateRollbackTest {

	@Autowired
	private ChatRoomService chatRoomService;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@MockBean
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomRepository.deleteAll();
	}

	@Test
	void rollsBackRoomCreationWhenMemberSaveFails() {
		when(chatRoomMemberRepository.save(any())).thenThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> chatRoomService.createChatRoom("study", "alice"))
				.isInstanceOf(RuntimeException.class);

		assertThat(chatRoomRepository.findAll()).isEmpty();
	}
}
