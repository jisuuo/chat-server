package jissuo.chat.user.domain;

import java.time.Instant;

public interface UserRepository {

    long save(Nickname nickname, Instant createdAt);
}
