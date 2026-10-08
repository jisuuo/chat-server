package jissuo.chat.room.infra.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "rooms")
class RoomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private Long createdBy;
    private Long lastMessageId;
    private LocalDateTime createdAt;

    protected RoomEntity() {
    }

    RoomEntity(String name, long createdBy, LocalDateTime createdAt) {
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getName() { return name; }
    Long getCreatedBy() { return createdBy; }
    Long getLastMessageId() { return lastMessageId; }
    LocalDateTime getCreatedAt() { return createdAt; }
}
