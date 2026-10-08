package jissuo.chat.user.infra.jpa;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import jissuo.chat.user.domain.Nickname;
import jissuo.chat.user.domain.User;
import jissuo.chat.user.domain.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository users;

    public JpaUserRepository(SpringDataUserRepository users) {
        this.users = users;
    }

    @Override
    public long save(Nickname nickname, Instant createdAt) {
        // ADR-038: 도메인 record를 매핑에서 분리하고 JDBC와 같은 UTC 시각을 저장한다.
        return users.save(new UserEntity(nickname.value(), LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))).getId();
    }

    @Override
    public List<User> findAllById(Collection<Long> ids) {
        return users.findAllById(ids).stream()
                .map(e -> new User(e.getId(), new Nickname(e.getNickname())))
                .toList();
    }
}
