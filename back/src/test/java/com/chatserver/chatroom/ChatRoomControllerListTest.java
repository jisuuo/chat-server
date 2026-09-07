package com.chatserver.chatroom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerListTest {

	@Autowired
	private MockMvc mockMvc;

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
	void emptyListReturns200WithEmptyArray() throws Exception {
		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void listsOnlyRoomsUserActivelyBelongsTo() throws Exception {
		ChatRoom roomA = chatRoomRepository.save(new ChatRoom("A", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(roomA.getId(), "alice", OffsetDateTime.now()));

		ChatRoom roomB = chatRoomRepository.save(new ChatRoom("B", "bob", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(roomB.getId(), "bob", OffsetDateTime.now()));

		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].name").value("A"))
				.andExpect(jsonPath("$.items[0].lastMessage").doesNotExist());
	}

	@Test
	void sortsByCreatedAtDescWhenNoMessages() throws Exception {
		ChatRoom older = chatRoomRepository.save(
				new ChatRoom("older", "alice", OffsetDateTime.now().minusMinutes(10)));
		chatRoomMemberRepository.save(new ChatRoomMember(older.getId(), "alice", OffsetDateTime.now()));
		ChatRoom newer = chatRoomRepository.save(new ChatRoom("newer", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(newer.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].name").value("newer"))
				.andExpect(jsonPath("$.items[1].name").value("older"));
	}

	@Test
	void paginatesWithCursor() throws Exception {
		for (int i = 0; i < 3; i++) {
			ChatRoom room = chatRoomRepository.save(
					new ChatRoom("room-" + i, "alice", OffsetDateTime.now().minusMinutes(3 - i)));
			chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		}

		MvcResult firstPage = mockMvc.perform(get("/api/chat-rooms?limit=2").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.nextCursor").exists())
				.andReturn();

		String body = firstPage.getResponse().getContentAsString();
		String nextCursor = JsonPath.read(body, "$.nextCursor");

		mockMvc.perform(get("/api/chat-rooms?limit=2&cursor=" + nextCursor).header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void limitOverMaxReturns400() throws Exception {
		mockMvc.perform(get("/api/chat-rooms?limit=101").header("X-User-Id", "alice"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void invalidCursorReturns400() throws Exception {
		mockMvc.perform(get("/api/chat-rooms?cursor=not-a-valid-cursor").header("X-User-Id", "alice"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}
}
