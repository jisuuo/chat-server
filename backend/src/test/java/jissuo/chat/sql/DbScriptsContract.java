package jissuo.chat.sql;

import static org.assertj.core.api.Assertions.assertThat;

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
