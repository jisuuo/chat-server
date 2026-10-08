package jissuo.chat.room.infra.jpa;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
class MembershipId implements Serializable {

    private Long roomId;
    private Long userId;

    protected MembershipId() {
    }

    MembershipId(long roomId, long userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    Long getRoomId() { return roomId; }
    Long getUserId() { return userId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MembershipId that)) return false;
        return Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, userId);
    }
}
