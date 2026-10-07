package jissuo.chat.user.api;

import jakarta.validation.Valid;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.user.application.UserService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 없이 사용자를 만들 수 있으므로 prod에서는 빈 자체를 만들지 않는다. 그때 이 경로는 404가 된다.
 */
@RestController
@Profile({"local", "bench"})
public class DevUserController {

    private final UserService userService;

    public DevUserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/api/dev/users")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(UserResponse.from(userService.create(request.nickname())));
    }
}
