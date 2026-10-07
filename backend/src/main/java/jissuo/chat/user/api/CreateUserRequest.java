package jissuo.chat.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jissuo.chat.user.domain.Nickname;
import org.hibernate.validator.constraints.CodePointLength;

// ADR-045: 도메인 Nickname까지 가면 500이 되므로 여기서 400으로 먼저 막는다.
// ADR-048: @Size는 이모지를 2자로 세서 DB 한도보다 엄격하므로 코드 포인트로 센다.
// ADR-050: 문자 규칙은 도메인과 어긋나지 않도록 Nickname의 정규식을 그대로 쓴다
public record CreateUserRequest(
        @NotBlank @CodePointLength(max = 50) @Pattern(regexp = Nickname.ALLOWED) String nickname
) {
}
