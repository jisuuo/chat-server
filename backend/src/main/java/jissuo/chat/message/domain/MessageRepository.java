package jissuo.chat.message.domain;

import java.time.Instant;
import java.util.List;
import jissuo.chat.room.domain.JoinBoundary;

public interface MessageRepository {

    Message save(long roomId, long senderId, MessageContent content, Instant createdAt);

    /** 결과는 커서 방향과 관계없이 항상 오래된 메시지부터 최신 메시지 순서다. */
    List<Message> find(long roomId, JoinBoundary boundary, MessageCursor cursor, int limit);
}
