package jissuo.chat.user.infra.jdbc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import jissuo.chat.user.domain.Nickname;
import jissuo.chat.user.domain.User;
import jissuo.chat.user.domain.UserRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jdbc", matchIfMissing = true)
public class JdbcUserRepository implements UserRepository {

    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long save(Nickname nickname, Instant createdAt) {
        var keyHolder = new GeneratedKeyHolder();
        // 컬럼 이름을 주지 않으면 PostgreSQL 드라이버는 행의 모든 컬럼을 키로 돌려줘서 getKey()가 실패한다(측정)
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:nickname, :createdAt)")
                .param("nickname", nickname.value())
                // 계획 1 세부 2: DB에는 UTC 기준 LocalDateTime으로 저장한다
                .param("createdAt", LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .update(keyHolder, "id");
        // MySQL 드라이버는 키를 BigInteger로 준다(측정). getKeyAs(Long.class)는 형 변환에 실패하므로 Number로 받는다
        return keyHolder.getKey().longValue();
    }

    @Override
    public List<User> findAllById(Collection<Long> ids) {
        return jdbc.sql("SELECT id, nickname FROM users WHERE id IN (:ids)")
                .param("ids", ids)
                .query((rs, n) -> new User(rs.getLong("id"), new Nickname(rs.getString("nickname"))))
                .list();
    }
}
