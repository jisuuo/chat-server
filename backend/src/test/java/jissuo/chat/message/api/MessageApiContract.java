package jissuo.chat.message.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import jissuo.chat.room.domain.AccessDeniedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

@RecordApplicationEvents
abstract class MessageApiContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEvents events;

    long sender;
    long room;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        sender = addUser("보내는 사람");
        room = createRoom("대화방");
    }

    @Test
    void 전송하면_201이고_방_목록의_맨_위로_올라간다() throws Exception {
        long another = createRoom("다른 방");
        send(sender, another, "먼저 보낸 메시지");
        long id = send(sender, room, "안녕\n😀");

        mvc.perform(get("/api/rooms").header("X-User-Id", sender))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms[0].id").value(room))
                .andExpect(jsonPath("$.data.rooms[0].lastMessageId").value(id));
        assertThat(jdbc.sql("SELECT last_message_id FROM rooms WHERE id = :id")
                .param("id", room).query(Long.class).single()).isEqualTo(id);
    }

    @Test
    void 최신_이전_이후_조회는_오래된_순서로_보내고_hasMore를_표시한다() throws Exception {
        long a = send(sender, room, "a");
        long b = send(sender, room, "b");
        long c = send(sender, room, "c");
        long d = send(sender, room, "d");

        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].id").value(c))
                .andExpect(jsonPath("$.data.messages[1].id").value(d))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("before", Long.toString(c)).param("size", "1"))
                .andExpect(jsonPath("$.data.messages[0].id").value(b))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("after", Long.toString(a)).param("size", "2"))
                .andExpect(jsonPath("$.data.messages[0].id").value(b))
                .andExpect(jsonPath("$.data.messages[1].id").value(c))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("after", Long.toString(d)))
                .andExpect(jsonPath("$.data.messages.length()").value(0))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void 비멤버의_전송과_조회는_403이고_인가_실패_이벤트가_발행된다() throws Exception {
        long stranger = addUser("다른 사람");
        mvc.perform(post("/api/rooms/{roomId}/messages", room).header("X-User-Id", stranger)
                        .contentType("application/json").content("{\"content\":\"안녕\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", stranger))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
        assertThat(events.stream(AccessDeniedEvent.class).filter(event -> event.userId() == stranger).toList())
                .hasSize(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM messages WHERE room_id = :id")
                .param("id", room).query(Long.class).single()).isZero();
    }

    @Test
    void 재입장하면_이전_메시지가_보이지_않고_이후_메시지는_폴링으로_보인다() throws Exception {
        long member = addUser("재입장자");
        mvc.perform(post("/api/rooms/{roomId}/members", room).header("X-User-Id", member))
                .andExpect(status().isCreated());
        send(sender, room, "입장 후 첫 메시지");
        mvc.perform(delete("/api/rooms/{roomId}/members/me", room).header("X-User-Id", member))
                .andExpect(status().isOk());
        long duringAbsence = send(sender, room, "나간 동안");
        mvc.perform(post("/api/rooms/{roomId}/members", room).header("X-User-Id", member))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", member))
                .andExpect(jsonPath("$.data.messages.length()").value(0));

        long afterRejoin = send(sender, room, "재입장 후");
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", member)
                        .param("after", Long.toString(duringAbsence)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(1))
                .andExpect(jsonPath("$.data.messages[0].id").value(afterRejoin));
    }

    @Test
    void 잘못된_본문과_조회_파라미터는_400() throws Exception {
        for (String body : new String[] {"{}", "{\"content\":\"\"}",
                "{\"content\":\"" + "😀".repeat(1001) + "\"}",
                "{\"content\":\"a\\u0000b\"}", "{\"content\":\"a\\ud800b\"}"}) {
            mvc.perform(post("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        for (String size : new String[] {"0", "101"}) {
            mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                            .param("size", size))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("after", "0").param("before", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/rooms/{roomId}/messages", room).header("X-User-Id", sender)
                        .param("after", "-1"))
                .andExpect(status().isBadRequest());
    }

    private long addUser(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:name, :at)")
                .param("name", nickname).param("at", LocalDateTime.parse("2026-10-07T01:02:03"))
                .update(key, "id");
        return key.getKey().longValue();
    }

    private long createRoom(String name) throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", sender)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM rooms WHERE name = :name").param("name", name)
                .query(Long.class).single();
    }

    private long send(long user, long roomId, String content) throws Exception {
        mvc.perform(post("/api/rooms/{roomId}/messages", roomId).header("X-User-Id", user)
                        .contentType("application/json")
                        .content("{\"content\":\"" + content.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.content").value(content));
        return jdbc.sql("SELECT id FROM messages WHERE room_id = :roomId AND content = :content")
                .param("roomId", roomId).param("content", content).query(Long.class).single();
    }
}
