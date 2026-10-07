package jissuo.chat.room.api;

import jissuo.chat.room.domain.Membership;

public record MemberResponse(long roomId, long userId) {

    static MemberResponse from(Membership membership) {
        return new MemberResponse(membership.roomId(), membership.userId());
    }
}
