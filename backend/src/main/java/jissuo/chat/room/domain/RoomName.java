package jissuo.chat.room.domain;

import java.util.regex.Pattern;

/**
 * 컨트롤러가 먼저 400으로 막고, 이 검사는 안전망이다 (ADR-045).
 * 두 검사가 어긋나면 컨트롤러를 통과한 값이 여기서 500이 되므로 @NotBlank, @CodePointLength, @Pattern과 같은 방법으로 검사한다.
 */
public record RoomName(String value) {

    public static final int MAX_LENGTH = 50;

    // ADR-050: 닉네임과 같은 규칙이다. room은 user를 모르므로(의존 방향 규칙) Nickname.ALLOWED를 가져다 쓰지 않고 같은 식을 둔다
    public static final String ALLOWED = "[^\\p{Cc}\\p{Cs}]*";
    private static final Pattern ALLOWED_PATTERN = Pattern.compile(ALLOWED);

    public RoomName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("방 이름이 비어 있다");
        }
        if (!ALLOWED_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("방 이름에 쓸 수 없는 문자가 있다");
        }
        // ADR-048: 두 DB의 VARCHAR(50)은 코드 포인트로 센다
        int length = value.codePointCount(0, value.length());
        if (length > MAX_LENGTH) {
            throw new IllegalArgumentException("방 이름은 " + MAX_LENGTH + "자 이하여야 한다: " + length);
        }
    }
}
