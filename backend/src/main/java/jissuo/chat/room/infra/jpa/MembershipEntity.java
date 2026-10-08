package jissuo.chat.room.infra.jpa;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "room_members")
class MembershipEntity implements Persistable<MembershipId> {

    @EmbeddedId
    private MembershipId id;
    private Long joinedMessageId;
    private LocalDateTime joinedAt;

    @Transient
    private boolean newEntity = true;

    protected MembershipEntity() {
    }

    MembershipEntity(MembershipId id, Long joinedMessageId, LocalDateTime joinedAt) {
        this.id = id;
        this.joinedMessageId = joinedMessageId;
        this.joinedAt = joinedAt;
    }

    @Override
    public MembershipId getId() { return id; }
    @Override
    public boolean isNew() { return newEntity; }

    @PostPersist
    @PostLoad
    void markNotNew() { newEntity = false; }
    Long getJoinedMessageId() { return joinedMessageId; }
    LocalDateTime getJoinedAt() { return joinedAt; }
}
