package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerJoinTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@Autowired
	private ChatRoomService chatRoomService;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void joiningNonExistentRoomReturns404() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/999999/join").header("X-User-Id", "bob"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
	}

	@Test
	void joiningClosedRoomReturns409() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		room.close(OffsetDateTime.now());
		chatRoomRepository.save(room);

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CHAT_ROOM_CLOSED"));
	}

	@Test
	void duplicateJoinSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isOk());

		long activeCount = chatRoomMemberRepository.findAll().stream()
				.filter(m -> m.getUserId().equals("bob") && m.isActive())
				.count();
		assertThat(activeCount).isEqualTo(1);
	}

	@Test
	void concurrentJoinsLeaveExactlyOneActiveMembership() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		int threads = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					chatRoomService.join(room.getId(), "bob");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
		}
		ready.await();
		start.countDown();
		for (Future<?> future : futures) {
			future.get();
		}
		executor.shutdown();

		long activeCount = chatRoomMemberRepository.findAll().stream()
				.filter(m -> m.getUserId().equals("bob") && m.isActive())
				.count();
		assertThat(activeCount).isEqualTo(1);
	}
}
