package jissuo.chat.message.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.CurrentUser;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.MessageCursor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {

    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @PostMapping("/api/rooms/{roomId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    // 계획 7 세부 7A: 작업 13의 AOP 프록시가 가로채도록 public으로 둔다
    public ApiResponse<MessageResponse> send(@CurrentUser AuthUser user, @PathVariable long roomId,
                                             @Valid @RequestBody SendMessageRequest request) {
        DeliveryOrigin origin = DeliveryOrigin.start("rest");
        return ApiResponse.ok(MessageResponse.from(messages.send(user.id(), roomId, request.content(), origin)));
    }

    @GetMapping("/api/rooms/{roomId}/messages")
    ApiResponse<MessagePageResponse> read(@CurrentUser AuthUser user, @PathVariable long roomId,
                                          @RequestParam(required = false) @Min(0) Long after,
                                          @RequestParam(required = false) @Min(0) Long before,
                                          @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        if (after != null && before != null) {
            throw new ChatException(ErrorCode.INVALID_REQUEST);
        }
        return ApiResponse.ok(MessagePageResponse.from(
                messages.read(user.id(), roomId, MessageCursor.of(after, before), size)));
    }
}
