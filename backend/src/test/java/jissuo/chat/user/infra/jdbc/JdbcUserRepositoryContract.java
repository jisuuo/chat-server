package jissuo.chat.user.infra.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import jissuo.chat.user.domain.Nickname;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 두 DB에서 같은 결과를 내는지 보기 위해 DB별 하위 클래스가 이 테스트를 그대로 물려받는다.
 */
abstract class JdbcUserRepositoryContract {

    @Autowired
    JdbcUserRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    void 저장하면_닉네임과_UTC_시각이_마이크로초까지_남는다() {
        long id = repository.save(new Nickname("철수"), Instant.parse("2026-10-07T01:02:03.123456Z"));

        assertThat(nicknameOf(id)).isEqualTo("철수");
        assertThat(createdAtOf(id)).isEqualTo(LocalDateTime.parse("2026-10-07T01:02:03.123456"));
    }

    @Test
    void 저장할_때마다_다른_id를_돌려준다() {
        long first = repository.save(new Nickname("a"), Instant.EPOCH);
        long second = repository.save(new Nickname("b"), Instant.EPOCH);

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 이모지_50개_닉네임이_DB에_들어간다() {
        // ADR-048: 도메인이 받아 준 길이를 드라이버 문자셋까지 거쳐 DB도 받아 주는지 본다
        String emoji = "😀".repeat(50);

        long id = repository.save(new Nickname(emoji), Instant.EPOCH);

        assertThat(nicknameOf(id)).isEqualTo(emoji);
    }

    private String nicknameOf(long id) {
        return jdbc.sql("SELECT nickname FROM users WHERE id = :id").param("id", id).query(String.class).single();
    }

    private LocalDateTime createdAtOf(long id) {
        return jdbc.sql("SELECT created_at FROM users WHERE id = :id").param("id", id).query(LocalDateTime.class).single();
    }
}
