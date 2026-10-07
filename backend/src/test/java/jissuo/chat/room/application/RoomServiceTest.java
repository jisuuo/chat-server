package jissuo.chat.room.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.RoomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomServiceTest {

    @Test
    void 방_생성_응답과_저장_시각은_같은_마이크로초_정밀도를_쓴다() {
        RoomRepository rooms = mock(RoomRepository.class);
        MembershipRepository memberships = mock(MembershipRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        Instant clockTime = Instant.parse("2026-10-07T01:02:03.123456789Z");
        Instant storedTime = Instant.parse("2026-10-07T01:02:03.123456Z");
        when(rooms.save(any(), eq(7L), eq(storedTime))).thenReturn(11L);

        var service = new RoomService(rooms, memberships, Clock.fixed(clockTime, ZoneOffset.UTC), events);
        var created = service.create(7L, "잡담방");

        assertEquals(storedTime, created.createdAt());
        verify(rooms).save(created.name(), 7L, storedTime);
    }
}
