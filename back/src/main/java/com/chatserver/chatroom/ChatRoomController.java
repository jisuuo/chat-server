package com.chatserver.chatroom;

import java.security.Principal;

import com.chatserver.chatroom.dto.ChatRoomListResponse;
import com.chatserver.chatroom.dto.ChatRoomResponse;
import com.chatserver.chatroom.dto.CreateChatRoomRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/chat-rooms")
public class ChatRoomController {

	private final ChatRoomService chatRoomService;

	public ChatRoomController(ChatRoomService chatRoomService) {
		this.chatRoomService = chatRoomService;
	}

	@PostMapping
	public ResponseEntity<ChatRoomResponse> create(@Valid @RequestBody CreateChatRoomRequest request,
			Principal principal) {
		ChatRoom room = chatRoomService.createChatRoom(request.name(), principal.getName());
		ChatRoomResponse body = new ChatRoomResponse(room.getId(), room.getName(), room.getCreatedAt());
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}

	@PostMapping("/{roomId}/join")
	public ResponseEntity<Void> join(@PathVariable Long roomId, Principal principal) {
		chatRoomService.join(roomId, principal.getName());
		return ResponseEntity.ok().build();
	}

	@PostMapping("/{roomId}/leave")
	public ResponseEntity<Void> leave(@PathVariable Long roomId, Principal principal) {
		chatRoomService.leave(roomId, principal.getName());
		return ResponseEntity.ok().build();
	}

	@GetMapping
	public ResponseEntity<ChatRoomListResponse> list(
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			Principal principal) {
		return ResponseEntity.ok(chatRoomService.listMyRooms(principal.getName(), cursor, limit));
	}
}
