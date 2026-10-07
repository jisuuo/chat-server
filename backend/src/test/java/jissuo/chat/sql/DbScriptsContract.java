package jissuo.chat.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** 두 DB의 스키마 A/B에서 SQL이 실행되고 정합성 규칙(I1~I7)을 지키는지 검사한다. */
abstract class DbScriptsContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    abstract SqlCli cli();

    abstract String dbDir();

    abstract String schema();

    /** ADR-041의 빈 DB와 같이 id가 1부터 다시 시작하도록 비운다. */
    abstract String resetSql();

    String messageTable() {
        return schema().equals("a") ? "messages" : "messages_b";
    }

    @BeforeEach
    void reset() {
        cli().runSql(resetSql());
    }

    @Test
    void seed는_정합성_규칙을_지킨다() {
        loadSeed();

        assertNoViolations();
        assertThat(count("users")).isEqualTo(3);
        assertThat(count("rooms")).isEqualTo(3);
        assertThat(count("room_members")).isEqualTo(6);
        assertThat(count(messageTable())).isEqualTo(7);
    }

    @Test
    void seed의_재입장한_민수는_재입장_이후_메시지만_본다() throws Exception {
        loadSeed();

        mvc.perform(get("/api/rooms/{roomId}/messages", roomId("잡담방")).header("X-User-Id", userId("민수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[*].content").value(contains("잡담 4", "잡담 5")))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void seed의_방_목록은_최근_대화순이고_빈_방은_마지막이다() throws Exception {
        loadSeed();

        mvc.perform(get("/api/rooms").header("X-User-Id", userId("철수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms[*].name").value(contains("스터디", "잡담방", "빈 방")));
    }

    @Test
    void bulk는_정합성_규칙과_구간별_분포를_지킨다() {
        cli().runFile("db/bulk/" + dbDir() + "/base.sql", Map.of());
        cli().runFile("db/bulk/" + dbDir() + "/messages_" + schema() + ".sql", Map.of("messages", "1000"));

        assertNoViolations();
        assertThat(count("users")).isEqualTo(10_000);
        assertThat(count("rooms")).isEqualTo(10_000);
        assertThat(count("room_members")).isEqualTo(100 * 50 + 900 * 20 + 9_000 * 5);
        assertThat(count(messageTable())).isEqualTo(1_000);
        List<List<String>> distribution =
                cli().runFile("db/queries/" + dbDir() + "/check_distribution.sql", Map.of());
        assertThat(distribution).extracting(row -> row.get(0) + "=" + row.get(2))
                .containsExactly("cold=20.00", "hot=50.00", "warm=30.00");
    }

    @Test
    void 확인용_쿼리는_bulk_데이터에서_오류_없이_실행된다() {
        cli().runFile("db/bulk/" + dbDir() + "/base.sql", Map.of());
        cli().runFile("db/bulk/" + dbDir() + "/messages_" + schema() + ".sql", Map.of("messages", "1000"));

        assertThat(cli().runFile("db/queries/" + dbDir() + "/explain_" + schema() + ".sql", Map.of())).isNotEmpty();
        assertThat(cli().runFile("db/queries/" + dbDir() + "/sizes.sql", Map.of())).isNotEmpty();
        assertThat(cli().runFile("db/queries/" + dbDir() + "/cache_hit.sql", Map.of())).isNotEmpty();
    }

    void loadSeed() {
        cli().runFile("db/seed/base.sql", Map.of());
        cli().runFile("db/seed/messages_" + schema() + ".sql", Map.of());
    }

    void assertNoViolations() {
        List<List<String>> rows = cli().runFile("db/queries/" + dbDir() + "/check_invariants.sql", Map.of());
        assertThat(rows).extracting(row -> row.get(0)).containsExactly("I1", "I2", "I3", "I4", "I5", "I6", "I7");
        assertThat(rows).allSatisfy(row -> assertThat(row.get(1)).as(row.get(0)).isEqualTo("0"));
    }

    long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    long userId(String nickname) {
        return jdbc.sql("SELECT id FROM users WHERE nickname = :n").param("n", nickname).query(Long.class).single();
    }

    long roomId(String name) {
        return jdbc.sql("SELECT id FROM rooms WHERE name = :n").param("n", name).query(Long.class).single();
    }
}
