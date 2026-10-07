package jissuo.chat.auth;

import java.util.regex.Pattern;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 헤더 값을 그대로 userId로 믿는다 (ADR-005). JWT를 도입하기 전까지 쓰는 임시 방식이다.
 * 폴링 요청마다 쿼리가 늘면 커넥션 풀이 고갈되는 문제(F1)가 앞당겨지므로, DB는 조회하지 않고 형식만 검사한다.
 */
@Component
public class HeaderUserIdAuthenticator implements Authenticator {

    // 한 사용자를 가리키는 표기를 하나로 맞춘다. Long.parseLong은 "+5", "007", 유니코드 숫자도 받아 주기 때문이다 (ADR-046)
    private static final Pattern USER_ID = Pattern.compile("[1-9][0-9]{0,18}");

    @Override
    public AuthUser authenticate(String credential) {
        if (credential == null || !USER_ID.matcher(credential).matches()) {
            throw new ChatException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return new AuthUser(Long.parseLong(credential));
        } catch (NumberFormatException e) {
            // 정규식은 자릿수만 제한하므로 Long 범위를 넘는 19자리 값이 여기까지 온다
            throw new ChatException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
