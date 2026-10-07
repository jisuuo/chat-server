package jissuo.chat.user.domain;

import java.util.regex.Pattern;

/**
 * 컨트롤러가 먼저 400으로 막고, 이 검사는 안전망이다 (ADR-045).
 * 두 검사가 어긋나면 컨트롤러를 통과한 값이 여기서 500이 되므로 @NotBlank, @CodePointLength, @Pattern과 같은 방법으로 검사한다.
 */
public record Nickname(String value) {

    public static final int MAX_LENGTH = 50;

    // ADR-050, F25: 짝 없는 서로게이트는 UTF-8로 바꿀 수 없어 두 DB 모두 ?로 저장되고(응답과 DB 값이 달라짐),
    // NUL은 PostgreSQL만 거절해서 500이 났다(측정). 한 줄짜리 이름이므로 탭과 줄바꿈 같은 다른 제어 문자도 막는다
    public static final String ALLOWED = "[^\\p{Cc}\\p{Cs}]*";
    private static final Pattern ALLOWED_PATTERN = Pattern.compile(ALLOWED);

    public Nickname {
        // ADR-049: 공백뿐인 닉네임은 화면에서 누가 보냈는지 알아볼 수 없다
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("닉네임이 비어 있다");
        }
        if (!ALLOWED_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("닉네임에 쓸 수 없는 문자가 있다");
        }
        // ADR-048: 두 DB의 VARCHAR(50)은 코드 포인트로 센다. String.length()는 이모지를 2로 센다 (측정)
        int length = value.codePointCount(0, value.length());
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("닉네임은 " + MAX_LENGTH + "자 이하여야 한다: " + length);
        }
    }
}
