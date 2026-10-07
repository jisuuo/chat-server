package jissuo.chat.message.application;

import java.time.Clock;
import java.util.List;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.AccessDeniedEvent;
import jissuo.chat.room.domain.Membership;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.RoomRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MessageService {

    private final MessageRepository messages;
    private final MembershipRepository memberships;
    private final RoomRepository rooms;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public MessageService(MessageRepository messages, MembershipRepository memberships, RoomRepository rooms,
                          Clock clock, ApplicationEventPublisher events) {
        this.messages = messages;
        this.memberships = memberships;
        this.rooms = rooms;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public Message send(long userId, long roomId, String content) {
        requireMembership(userId, roomId);
        Message saved = messages.save(roomId, userId, new MessageContent(content), clock.instant());
        // R7: 방 목록 정렬값은 메시지와 같은 트랜잭션에서 전진시킨다.
        rooms.advanceLastMessageId(roomId, saved.id());
        return saved;
    }

    public MessagePage read(long userId, long roomId, MessageCursor cursor, int size) {
        Membership membership = requireMembership(userId, roomId);
        List<Message> found = messages.find(roomId, membership.boundary(), cursor, size + 1);
        boolean hasMore = found.size() > size;
        if (!hasMore) {
            return new MessagePage(found, false);
        }
        // 최신·before는 역순으로 n+1건을 고른 뒤 저장소에서 다시 오름차순으로 돌려준다.
        // 따라서 초과한 한 건은 앞쪽에 있고, after에서는 뒤쪽에 있다.
        List<Message> page = cursor.direction() == MessageCursor.Direction.AFTER
                ? found.subList(0, size) : found.subList(1, found.size());
        return new MessagePage(page, true);
    }

    private Membership requireMembership(long userId, long roomId) {
        return memberships.find(roomId, userId).orElseThrow(() -> {
            events.publishEvent(new AccessDeniedEvent(roomId, userId,
                    ErrorCode.NOT_A_MEMBER.name(), clock.instant()));
            return new ChatException(ErrorCode.NOT_A_MEMBER);
        });
    }
}
