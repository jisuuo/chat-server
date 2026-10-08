package jissuo.chat.room.domain;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository {

    void save(Membership membership);

    Optional<Membership> find(long roomId, long userId);

    /** 계획 7 세부 4: fan-out 대상. 오름차순 */
    List<Long> findUserIds(long roomId);

    /** @return 지운 행이 있으면 true */
    boolean delete(long roomId, long userId);
}
