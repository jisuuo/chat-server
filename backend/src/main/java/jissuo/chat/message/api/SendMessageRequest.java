package jissuo.chat.message.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jissuo.chat.message.domain.MessageContent;
import org.hibernate.validator.constraints.CodePointLength;

// ADR-045, 048, 052: 도메인 안전망과 같은 입력 규칙을 여기서 검사해 400으로 돌린다.
public record SendMessageRequest(
        @NotNull @CodePointLength(min = 1, max = MessageContent.MAX_LENGTH)
        @Pattern(regexp = MessageContent.ALLOWED) String content
) {
}
