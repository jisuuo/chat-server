package jissuo.chat.room.domain;

import java.time.Instant;

/**
 * 이 멤버가 어느 메시지부터 볼 수 있는지 (R4, ADR-008).
 * 번호와 시각을 둘 다 남기고, 어느 쪽으로 거를지는 chat.join-boundary 설정으로 고른다 (F18).
 */
public record JoinBoundary(long messageId, Instant joinedAt) {

    public static JoinBoundary at(Long lastMessageId, Instant now) {
        // 메시지가 한 번도 없던 방이면 이후의 모든 메시지가 보여야 하므로 0이다
        return new JoinBoundary(lastMessageId == null ? 0 : lastMessageId, now);
    }
}
