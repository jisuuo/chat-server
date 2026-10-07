package jissuo.chat.room.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jissuo.chat.room.domain.RoomName;
import org.hibernate.validator.constraints.CodePointLength;

// ADR-045, 048, 050: 도메인 안전망과 같은 길이·문자 규칙을 입력 단계에서 검사해 400으로 돌린다.
public record CreateRoomRequest(
        @NotBlank @CodePointLength(max = RoomName.MAX_LENGTH) @Pattern(regexp = RoomName.ALLOWED) String name
) {
}
