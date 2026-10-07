package jissuo.chat.room.domain;

public record Membership(long roomId, long userId, JoinBoundary boundary) {
}
