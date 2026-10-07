package jissuo.chat.room.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.room.domain.AccessDeniedEvent;
import jissuo.chat.room.domain.JoinBoundary;
import jissuo.chat.room.domain.MemberJoinedEvent;
import jissuo.chat.room.domain.MemberLeftEvent;
import jissuo.chat.room.domain.Membership;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.Room;
import jissuo.chat.room.domain.RoomCreatedEvent;
import jissuo.chat.room.domain.RoomListCursor;
import jissuo.chat.room.domain.RoomName;
import jissuo.chat.room.domain.RoomRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomRepository rooms;
    private final MembershipRepository memberships;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public RoomService(RoomRepository rooms, MembershipRepository memberships, Clock clock,
                       ApplicationEventPublisher events) {
        this.rooms = rooms;
        this.memberships = memberships;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public Room create(long userId, String name) {
        // ADR-053: 방 생성 응답과 DATETIME(6)/TIMESTAMP(6)에 저장된 시각을 맞춘다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RoomName roomName = new RoomName(name);
        long roomId = rooms.save(roomName, userId, now);
        // R1: 방과 생성자의 멤버십이 함께 커밋되어야 한다. 첫 입장 경계는 메시지 0이다.
        memberships.save(new Membership(roomId, userId, JoinBoundary.at(null, now)));
        events.publishEvent(new RoomCreatedEvent(roomId, userId, now));
        return new Room(roomId, roomName, userId, null, now);
    }

    public RoomPage list(RoomListCursor cursor, int size) {
        List<Room> found = rooms.findPage(cursor, size + 1);
        boolean hasMore = found.size() > size;
        List<Room> page = hasMore ? found.subList(0, size) : found;
        String nextCursor = null;
        if (hasMore) {
            Room last = page.getLast();
            nextCursor = new RoomListCursor(last.lastMessageId(), last.id()).format();
        }
        return new RoomPage(page, hasMore, nextCursor);
    }

    @Transactional
    public Membership join(long userId, long roomId) {
        Room room = rooms.findById(roomId).orElseThrow(() -> new ChatException(ErrorCode.ROOM_NOT_FOUND));
        Instant now = clock.instant();
        Membership membership = new Membership(roomId, userId, JoinBoundary.at(room.lastMessageId(), now));
        memberships.save(membership);
        events.publishEvent(new MemberJoinedEvent(roomId, userId, now));
        return membership;
    }

    @Transactional
    public void leave(long userId, long roomId) {
        Instant now = clock.instant();
        if (!memberships.delete(roomId, userId)) {
            events.publishEvent(new AccessDeniedEvent(roomId, userId, ErrorCode.NOT_A_MEMBER.name(), now));
            throw new ChatException(ErrorCode.NOT_A_MEMBER);
        }
        events.publishEvent(new MemberLeftEvent(roomId, userId, now));
    }
}
