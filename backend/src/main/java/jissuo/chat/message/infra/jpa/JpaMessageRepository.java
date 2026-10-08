package jissuo.chat.message.infra.jpa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.JoinBoundary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public class JpaMessageRepository implements MessageRepository {

    private final SpringDataMessageRepository messages;
    private final boolean timeBoundary;

    JpaMessageRepository(SpringDataMessageRepository messages, boolean timeBoundary) {
        this.messages = messages;
        this.timeBoundary = timeBoundary;
    }

    @Override
    public Message save(long roomId, long senderId, MessageContent content, Instant createdAt) {
        Instant storedAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        MessageEntity saved = messages.save(new MessageEntity(roomId, senderId, content.value(),
                LocalDateTime.ofInstant(storedAt, ZoneOffset.UTC)));
        return new Message(saved.getId(), roomId, senderId, content, storedAt);
    }

    @Override
    public List<Message> find(long roomId, JoinBoundary boundary, MessageCursor cursor, int limit) {
        Pageable page = PageRequest.ofSize(limit);
        List<MessageEntity> found = timeBoundary
                ? findByTime(roomId, boundary, cursor, page)
                : findById(roomId, boundary, cursor, page);
        List<Message> result = new ArrayList<>(found.stream().map(JpaMessageRepository::toMessage).toList());
        if (cursor.direction() != MessageCursor.Direction.AFTER) {
            // ADR-017: 최근 n건을 선택한 뒤 응답만 오래된 순서로 돌려준다.
            Collections.reverse(result);
        }
        return result;
    }

    private List<MessageEntity> findById(long roomId, JoinBoundary boundary, MessageCursor cursor, Pageable page) {
        long boundaryId = boundary.messageId();
        return switch (cursor.direction()) {
            case LATEST -> messages.findByRoomIdAndIdGreaterThanOrderByIdDesc(roomId, boundaryId, page);
            case BEFORE -> messages.findByRoomIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(
                    roomId, boundaryId, cursor.id(), page);
            case AFTER -> messages.findByRoomIdAndIdGreaterThanOrderByIdAsc(
                    roomId, Math.max(boundaryId, cursor.id()), page);
        };
    }

    private List<MessageEntity> findByTime(long roomId, JoinBoundary boundary, MessageCursor cursor, Pageable page) {
        LocalDateTime joinedAt = LocalDateTime.ofInstant(boundary.joinedAt(), ZoneOffset.UTC);
        return switch (cursor.direction()) {
            case LATEST -> messages.findByRoomIdAndCreatedAtGreaterThanOrderByIdDesc(roomId, joinedAt, page);
            case BEFORE -> messages.findByRoomIdAndCreatedAtGreaterThanAndIdLessThanOrderByIdDesc(
                    roomId, joinedAt, cursor.id(), page);
            case AFTER -> messages.findByRoomIdAndCreatedAtGreaterThanAndIdGreaterThanOrderByIdAsc(
                    roomId, joinedAt, cursor.id(), page);
        };
    }

    private static Message toMessage(MessageEntity e) {
        return new Message(e.getId(), e.getRoomId(), e.getSenderId(),
                new MessageContent(e.getContent()), e.getCreatedAt().toInstant(ZoneOffset.UTC));
    }
}
