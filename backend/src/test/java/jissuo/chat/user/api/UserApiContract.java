package jissuo.chat.user.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 두 DB에서 같은 결과를 내는지 보기 위해 DB별 하위 클래스가 이 테스트를 그대로 물려받는다.
 */
abstract class UserApiContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        // FK를 따라 자식 테이블부터 지운다
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
    }

    @Test
    void 여러_id의_닉네임을_조회한다() throws Exception {
        long a = addUser("철수");
        long b = addUser("영희");
        mvc.perform(get("/api/users").param("ids", a + "," + b + "," + Long.MAX_VALUE).header("X-User-Id", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id == " + b + ")].nickname").value("영희"));
    }

    @Test
    void id가_없거나_100개를_넘으면_400() throws Exception {
        long a = addUser("철수");
        mvc.perform(get("/api/users").header("X-User-Id", a))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        String many = LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(Collectors.joining(","));
        mvc.perform(get("/api/users").param("ids", many).header("X-User-Id", a))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private long addUser(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:name, :at)")
                .param("name", nickname).param("at", LocalDateTime.parse("2026-10-07T01:02:03"))
                .update(key, "id");
        return key.getKey().longValue();
    }
}
