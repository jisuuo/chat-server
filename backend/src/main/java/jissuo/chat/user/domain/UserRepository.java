package jissuo.chat.user.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface UserRepository {

    long save(Nickname nickname, Instant createdAt);

    List<User> findAllById(Collection<Long> ids);
}
