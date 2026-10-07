package jissuo.chat.user.api;

import jissuo.chat.user.domain.User;

public record UserResponse(long id, String nickname) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.nickname().value());
    }
}
