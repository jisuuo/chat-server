package jissuo.chat.room.domain;

import java.util.Optional;

public interface MembershipRepository {

    void save(Membership membership);

    Optional<Membership> find(long roomId, long userId);

    /** @return 지운 행이 있으면 true */
    boolean delete(long roomId, long userId);
}
