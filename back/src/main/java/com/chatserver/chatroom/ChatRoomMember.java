package com.chatserver.chatroom;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_room_member")
public class ChatRoomMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "chat_room_id", nullable = false)
	private Long chatRoomId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "joined_at", nullable = false)
	private OffsetDateTime joinedAt;

	@Column(name = "left_at")
	private OffsetDateTime leftAt;

	protected ChatRoomMember() {
	}

	public ChatRoomMember(Long chatRoomId, String userId, OffsetDateTime joinedAt) {
		this.chatRoomId = chatRoomId;
		this.userId = userId;
		this.joinedAt = joinedAt;
	}

	public boolean isActive() {
		return leftAt == null;
	}

	public void leave(OffsetDateTime leftAt) {
		this.leftAt = leftAt;
	}

	public Long getId() {
		return id;
	}

	public Long getChatRoomId() {
		return chatRoomId;
	}

	public String getUserId() {
		return userId;
	}

	public OffsetDateTime getJoinedAt() {
		return joinedAt;
	}

	public OffsetDateTime getLeftAt() {
		return leftAt;
	}
}
