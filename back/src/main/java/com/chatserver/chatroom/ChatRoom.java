package com.chatserver.chatroom;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_room")
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "closed_at")
	private OffsetDateTime closedAt;

	@Column(name = "last_message_at")
	private OffsetDateTime lastMessageAt;

	protected ChatRoom() {
	}

	public ChatRoom(String name, String createdBy, OffsetDateTime createdAt) {
		this.name = name;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	public boolean isClosed() {
		return closedAt != null;
	}

	public void close(OffsetDateTime closedAt) {
		this.closedAt = closedAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getClosedAt() {
		return closedAt;
	}

	public OffsetDateTime getLastMessageAt() {
		return lastMessageAt;
	}
}
