package jissuo.chat.room.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.CurrentUser;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.room.application.RoomService;
import jissuo.chat.room.domain.RoomListCursor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoomController {

    private final RoomService rooms;

    public RoomController(RoomService rooms) {
        this.rooms = rooms;
    }

    @PostMapping("/api/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<RoomResponse> create(@CurrentUser AuthUser user, @Valid @RequestBody CreateRoomRequest request) {
        return ApiResponse.ok(RoomResponse.from(rooms.create(user.id(), request.name())));
    }

    @GetMapping("/api/rooms")
    ApiResponse<RoomPageResponse> list(@CurrentUser AuthUser user,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        RoomListCursor parsed = null;
        if (cursor != null) {
            try {
                parsed = RoomListCursor.parse(cursor);
            } catch (IllegalArgumentException e) {
                // ADR-045: 형식뿐 아니라 Long 범위를 넘는 숫자도 잘못된 입력이다.
                throw new ChatException(ErrorCode.INVALID_REQUEST);
            }
        }
        return ApiResponse.ok(RoomPageResponse.from(rooms.list(parsed, size)));
    }

    @PostMapping("/api/rooms/{roomId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    ApiResponse<MemberResponse> join(@CurrentUser AuthUser user, @PathVariable long roomId) {
        return ApiResponse.ok(MemberResponse.from(rooms.join(user.id(), roomId)));
    }

    @DeleteMapping("/api/rooms/{roomId}/members/me")
    ApiResponse<Void> leave(@CurrentUser AuthUser user, @PathVariable long roomId) {
        rooms.leave(user.id(), roomId);
        return ApiResponse.ok(null);
    }
}
