package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.chatserver.chatroom.dto.CreateChatRoomRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerCreateTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

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
	void createsRoomAndAutoJoinsCreator() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.header("X-User-Id", "alice")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("study"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.roomId").exists())
				.andExpect(jsonPath("$.name").value("study"));

		List<ChatRoom> rooms = chatRoomRepository.findAll();
		assertThat(rooms).hasSize(1);
		List<ChatRoomMember> members = chatRoomMemberRepository.findAll();
		assertThat(members).hasSize(1);
		assertThat(members.get(0).getUserId()).isEqualTo("alice");
		assertThat(members.get(0).isActive()).isTrue();
	}

	@Test
	void rejectsBlankName() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.header("X-User-Id", "alice")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("   "))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void rejectsWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("study"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}
}
