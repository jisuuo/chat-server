package jissuo.chat.room.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import jissuo.chat.room.domain.AccessDeniedEvent;
import jissuo.chat.room.domain.MemberJoinedEvent;
import jissuo.chat.room.domain.MemberLeftEvent;
import jissuo.chat.room.domain.RoomCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@RecordApplicationEvents
abstract class RoomApiContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEvents events;

    long userId;

    @BeforeEach
    void setUp() {
        // FK를 따라 자식 테이블부터 지운다
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        userId = addUser("철수");
    }

    @Test
    void 생성자는_경계_0으로_입장하고_생성_이벤트가_발행된다() throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", userId)
                        .contentType("application/json").content("{\"name\":\"잡담방\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("잡담방"))
                .andExpect(jsonPath("$.data.createdBy").value(userId))
                .andExpect(jsonPath("$.data.lastMessageId").value(nullValue()));

        long roomId = roomId("잡담방");
        assertThat(jdbc.sql("SELECT joined_message_id FROM room_members WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId).param("userId", userId).query(Long.class).single()).isZero();
        assertThat(events.stream(RoomCreatedEvent.class).toList()).hasSize(1);
        RoomCreatedEvent created = events.stream(RoomCreatedEvent.class).findFirst().orElseThrow();
        assertThat(created.roomId()).isEqualTo(roomId);
        assertThat(created.userId()).isEqualTo(userId);
        assertThat(created.at()).isNotNull();
    }

    @Test
    void 없는_사용자가_만들면_401이고_방도_롤백된다() throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", Long.MAX_VALUE)
                        .contentType("application/json").content("{\"name\":\"실패방\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM rooms WHERE name = '실패방'").query(Long.class).single()).isZero();
    }

    @Test
    void 입장과_중복_입장과_나가기_흐름() throws Exception {
        long roomId = createRoom("방");
        long other = addUser("영희");
        jdbc.sql("UPDATE rooms SET last_message_id = 42 WHERE id = :id").param("id", roomId).update();

        mvc.perform(post("/api/rooms/{roomId}/members", roomId).header("X-User-Id", other))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.userId").value(other));
        assertThat(jdbc.sql("SELECT joined_message_id FROM room_members WHERE room_id = :roomId AND user_id = :userId")
                .param("roomId", roomId).param("userId", other).query(Long.class).single()).isEqualTo(42);
        assertThat(events.stream(MemberJoinedEvent.class).toList())
                .singleElement().satisfies(event -> {
                    assertThat(event.roomId()).isEqualTo(roomId);
                    assertThat(event.userId()).isEqualTo(other);
                    assertThat(event.at()).isNotNull();
                });

        mvc.perform(post("/api/rooms/{roomId}/members", roomId).header("X-User-Id", other))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_MEMBER"));
        mvc.perform(delete("/api/rooms/{roomId}/members/me", roomId).header("X-User-Id", other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(nullValue()));
        assertThat(events.stream(MemberLeftEvent.class).toList())
                .singleElement().satisfies(event -> {
                    assertThat(event.roomId()).isEqualTo(roomId);
                    assertThat(event.userId()).isEqualTo(other);
                });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM rooms WHERE id = :id").param("id", roomId)
                .query(Long.class).single()).isEqualTo(1);
        mvc.perform(delete("/api/rooms/{roomId}/members/me", roomId).header("X-User-Id", other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        assertThat(events.stream(AccessDeniedEvent.class).toList())
                .singleElement().satisfies(event -> {
                    assertThat(event.roomId()).isEqualTo(roomId);
                    assertThat(event.userId()).isEqualTo(other);
                    assertThat(event.code()).isEqualTo("NOT_A_MEMBER");
                });
    }

    @Test
    void 없는_방은_404이고_없는_사용자의_입장은_401() throws Exception {
        mvc.perform(post("/api/rooms/{roomId}/members", Long.MAX_VALUE).header("X-User-Id", userId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        long roomId = createRoom("방");
        mvc.perform(post("/api/rooms/{roomId}/members", roomId).header("X-User-Id", Long.MAX_VALUE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 목록은_최근_메시지순_커서로_넘기고_마지막에는_다음_커서가_없다() throws Exception {
        long a = createRoom("a");
        long b = createRoom("b");
        long c = createRoom("c");
        jdbc.sql("UPDATE rooms SET last_message_id = 10 WHERE id = :id").param("id", a).update();
        jdbc.sql("UPDATE rooms SET last_message_id = 20 WHERE id = :id").param("id", c).update();

        mvc.perform(get("/api/rooms").header("X-User-Id", userId).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms[0].id").value(c))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("20:" + c));
        mvc.perform(get("/api/rooms").header("X-User-Id", userId)
                        .param("size", "1").param("cursor", "20:" + c))
                .andExpect(jsonPath("$.data.rooms[0].id").value(a))
                .andExpect(jsonPath("$.data.nextCursor").value("10:" + a));
        mvc.perform(get("/api/rooms").header("X-User-Id", userId)
                        .param("size", "1").param("cursor", "10:" + a))
                .andExpect(jsonPath("$.data.rooms[0].id").value(b))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 메시지_없는_방_구간도_커서로_넘긴다() throws Exception {
        long a = createRoom("a");
        long b = createRoom("b");
        long c = createRoom("c");
        jdbc.sql("UPDATE rooms SET last_message_id = 10 WHERE id = :id").param("id", a).update();

        mvc.perform(get("/api/rooms").header("X-User-Id", userId)
                        .param("size", "1").param("cursor", "10:" + a))
                .andExpect(jsonPath("$.data.rooms[0].id").value(c))
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("-:" + c));
        mvc.perform(get("/api/rooms").header("X-User-Id", userId)
                        .param("size", "1").param("cursor", "-:" + c))
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.rooms[0].id").value(b))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 빈_목록은_더_볼_방과_다음_커서가_없다() throws Exception {
        mvc.perform(get("/api/rooms").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms").isArray())
                .andExpect(jsonPath("$.data.rooms.length()").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.nextCursor").value(nullValue()));
    }

    @Test
    void 이모지_50개_방_이름은_생성할_수_있다() throws Exception {
        String name = "😀".repeat(50);
        mvc.perform(post("/api/rooms").header("X-User-Id", userId)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(name));
    }

    @Test
    void 잘못된_입력은_400() throws Exception {
        for (String name : new String[] {"", " ", "😀".repeat(51), "a\\nb", "\\ud800"}) {
            mvc.perform(post("/api/rooms").header("X-User-Id", userId)
                            .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/rooms").header("X-User-Id", userId)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        // 커서는 서버가 준 값만 받는다. 빈 문자열을 "처음부터"로 봐 주지 않는다
        for (String cursor : new String[] {"", "bad", "999999999999999999999:1"}) {
            mvc.perform(get("/api/rooms").header("X-User-Id", userId).param("cursor", cursor))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        for (String size : new String[] {"0", "51"}) {
            mvc.perform(get("/api/rooms").header("X-User-Id", userId).param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
    }

    private long addUser(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:name, :at)")
                .param("name", nickname).param("at", LocalDateTime.parse("2026-10-07T01:02:03"))
                .update(key, "id");
        return key.getKey().longValue();
    }

    private long createRoom(String name) throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", userId)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        return roomId(name);
    }

    private long roomId(String name) {
        return jdbc.sql("SELECT id FROM rooms WHERE name = :name").param("name", name).query(Long.class).single();
    }
}
