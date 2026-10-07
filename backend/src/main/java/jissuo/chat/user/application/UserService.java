package jissuo.chat.user.application;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import jissuo.chat.user.domain.Nickname;
import jissuo.chat.user.domain.User;
import jissuo.chat.user.domain.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository users;
    private final Clock clock;

    public UserService(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    public User create(String nickname) {
        Nickname value = new Nickname(nickname);
        long id = users.save(value, clock.instant());
        return new User(id, value);
    }

    public List<User> findAll(Collection<Long> ids) {
        return users.findAllById(ids);
    }
}
