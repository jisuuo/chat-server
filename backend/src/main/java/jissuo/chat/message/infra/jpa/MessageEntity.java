package jissuo.chat.message.infra.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long roomId;
    private Long senderId;
    private String content;
    private LocalDateTime createdAt;

    protected MessageEntity() {
    }

    MessageEntity(long roomId, long senderId, String content, LocalDateTime createdAt) {
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    Long getRoomId() { return roomId; }
    Long getSenderId() { return senderId; }
    String getContent() { return content; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
