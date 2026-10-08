package jissuo.chat.user.api;

import java.util.List;
import java.util.Set;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.CurrentUser;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.user.application.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    // ADR-119: 화면이 한 번에 보이는 사람 수보다 넉넉하고, IN 목록이 무한히 커지지 않게 한다
    static final int MAX_IDS = 100;

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping("/api/users")
    ApiResponse<List<UserResponse>> list(@CurrentUser AuthUser user, @RequestParam(required = false) List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new ChatException(ErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.ok(users.findAll(Set.copyOf(ids)).stream().map(UserResponse::from).toList());
    }
}
