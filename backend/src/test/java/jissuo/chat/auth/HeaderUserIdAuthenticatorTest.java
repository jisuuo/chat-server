package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HeaderUserIdAuthenticatorTest {

    final Authenticator authenticator = new HeaderUserIdAuthenticator();

    @Test
    void 양의_정수면_그_id의_사용자다() {
        assertThat(authenticator.authenticate("42")).isEqualTo(new AuthUser(42));
    }

    @Test
    void Long_최댓값까지_받는다() {
        assertThat(authenticator.authenticate("9223372036854775807")).isEqualTo(new AuthUser(Long.MAX_VALUE));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "abc", "0", "-1", "+5", "007", " 5", "5 ", "1.5",
            "٥",                    // 아랍 숫자 5: Long.parseLong은 받아 준다
            "9223372036854775808",  // 정규식은 통과하지만 Long 범위를 넘는다
            "12345678901234567890"  // 정규식의 자릿수 제한에서 걸린다
    })
    void 형식이_틀리면_UNAUTHENTICATED(String credential) {
        assertThatThrownBy(() -> authenticator.authenticate(credential))
                .isInstanceOfSatisfying(ChatException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
