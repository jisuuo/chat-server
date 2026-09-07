package com.chatserver.chatroom;

import java.security.Principal;

import com.chatserver.chatroom.dto.ChatRoomResponse;
import com.chatserver.chatroom.dto.CreateChatRoomRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
