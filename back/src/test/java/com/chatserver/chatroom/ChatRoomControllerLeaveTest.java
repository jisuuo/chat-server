package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
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
class ChatRoomControllerLeaveTest {

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
	void leavingNonExistentRoomReturns404() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/999999/leave").header("X-User-Id", "alice"))
				.andExpect(status().isNotFound());
	}

	@Test
	void leavingWithoutActiveMembershipSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "bob"))
				.andExpect(status().isOk());
	}

	@Test
	void duplicateLeaveSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());
	}

	@Test
	void lastMemberLeavingClosesRoom() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isTrue();
	}

	@Test
	void leavingWithOtherActiveMembersDoesNotCloseRoom() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "bob", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isFalse();
	}

	@Test
	void concurrentLastTwoLeavesCloseRoomExactlyOnce() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "bob", OffsetDateTime.now()));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Callable<Void> leaveAlice = () -> {
			ready.countDown();
			start.await();
			chatRoomService.leave(room.getId(), "alice");
			return null;
		};
		Callable<Void> leaveBob = () -> {
			ready.countDown();
			start.await();
			chatRoomService.leave(room.getId(), "bob");
			return null;
		};

		Future<Void> f1 = executor.submit(leaveAlice);
		Future<Void> f2 = executor.submit(leaveBob);
		ready.await();
		start.countDown();
		f1.get();
		f2.get();
		executor.shutdown();

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isTrue();
		assertThat(chatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(room.getId())).isZero();
	}
}
