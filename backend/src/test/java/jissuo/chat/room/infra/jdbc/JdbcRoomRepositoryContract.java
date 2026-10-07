package jissuo.chat.room.infra.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.room.domain.Room;
import jissuo.chat.room.domain.RoomListCursor;
import jissuo.chat.room.domain.RoomName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 두 DB에서 같은 결과를 내는지 보기 위해 DB별 하위 클래스가 이 테스트를 그대로 물려받는다.
 */
abstract class JdbcRoomRepositoryContract {

    static final Instant AT = Instant.parse("2026-10-07T01:02:03.123456Z");

    @Autowired
    JdbcRoomRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clear() {
        // 목록 정렬은 테이블 전체를 보므로, 다른 테스트가 남긴 방이 있으면 기대 순서를 정할 수 없다
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
    }

    @Test
    void 저장한_방을_id로_찾으면_UTC_시각이_마이크로초까지_남는다() {
        long id = repository.save(new RoomName("잡담방"), 7, AT);

        assertThat(repository.findById(id)).contains(new Room(id, new RoomName("잡담방"), 7, null, AT));
    }

    @Test
    void 없는_방을_찾으면_비어_있다() {
        assertThat(repository.findById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void 이모지_50개_방_이름이_DB에_들어간다() {
        String emoji = "😀".repeat(50);

        long id = repository.save(new RoomName(emoji), 7, AT);

        assertThat(repository.findById(id).orElseThrow().name().value()).isEqualTo(emoji);
    }

    @Test
    void 마지막_메시지_번호는_더_큰_번호일_때만_바뀐다() {
        // ADR-016: 늦게 커밋된 작은 번호가 큰 번호를 덮어쓰지 않게 한다
        long id = repository.save(new RoomName("방"), 7, AT);

        repository.advanceLastMessageId(id, 5);
        assertThat(lastMessageIdOf(id)).isEqualTo(5L);

        repository.advanceLastMessageId(id, 3);
        assertThat(lastMessageIdOf(id)).isEqualTo(5L);

        repository.advanceLastMessageId(id, 5);
        assertThat(lastMessageIdOf(id)).isEqualTo(5L);

        repository.advanceLastMessageId(id, 9);
        assertThat(lastMessageIdOf(id)).isEqualTo(9L);
    }

    @Test
    void 마지막_메시지_번호를_바꿔도_다른_방은_그대로다() {
        long target = repository.save(new RoomName("a"), 7, AT);
        long other = repository.save(new RoomName("b"), 7, AT);

        repository.advanceLastMessageId(target, 5);

        assertThat(lastMessageIdOf(other)).isNull();
    }

    @Test
    void 목록은_최근_메시지순이고_메시지_없는_방은_뒤에서_생성_역순이다() {
        List<Long> expected = seedRooms();

        assertThat(ids(repository.findPage(null, 100))).isEqualTo(expected);
    }

    @Test
    void 커서로_끝까지_넘기면_누락도_중복도_없다() {
        List<Long> expected = seedRooms();

        for (int limit = 1; limit <= expected.size() + 1; limit++) {
            assertThat(pageThrough(limit)).as("limit=%d", limit).isEqualTo(expected);
        }
    }

    @Test
    void 방이_없으면_빈_목록이다() {
        assertThat(repository.findPage(null, 10)).isEmpty();
    }

    /**
     * 정렬의 모든 갈래(번호 내림차순, 같은 번호면 id 내림차순, NULL은 뒤에서 id 내림차순)가 나오도록 방을 만든다.
     * 같은 번호는 실제로는 생기지 않지만(메시지 id는 전체에서 하나씩 늘어난다) 커서의 두 번째 조건을 확인하려고 넣는다.
     */
    private List<Long> seedRooms() {
        long r1 = roomWith(10L);
        long r2 = roomWith(null);
        long r3 = roomWith(30L);
        long r4 = roomWith(null);
        long r5 = roomWith(20L);
        long r6 = roomWith(20L);
        return List.of(r3, r6, r5, r1, r4, r2);
    }

    private long roomWith(Long lastMessageId) {
        long id = repository.save(new RoomName("방"), 7, AT);
        if (lastMessageId != null) {
            repository.advanceLastMessageId(id, lastMessageId);
        }
        return id;
    }

    private List<Long> pageThrough(int limit) {
        List<Long> seen = new ArrayList<>();
        RoomListCursor cursor = null;
        while (true) {
            List<Room> page = repository.findPage(cursor, limit);
            seen.addAll(ids(page));
            if (page.size() < limit) {
                return seen;
            }
            Room last = page.getLast();
            // 실제 API처럼 문자열로 내보냈다가 다시 읽는다
            cursor = RoomListCursor.parse(new RoomListCursor(last.lastMessageId(), last.id()).format());
        }
    }

    private static List<Long> ids(List<Room> rooms) {
        return rooms.stream().map(Room::id).toList();
    }

    private Long lastMessageIdOf(long id) {
        return jdbc.sql("SELECT last_message_id FROM rooms WHERE id = :id").param("id", id)
                // single()은 값이 NULL인 행을 결과 없음으로 보고 실패한다(측정)
                .query((rs, n) -> rs.getObject(1, Long.class)).list().getFirst();
    }
}
