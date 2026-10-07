package jissuo.chat.message.domain;

/** 메시지 길이는 두 DB의 VARCHAR(1000)과 같은 코드 포인트 기준으로 센다 (ADR-048). */
public record MessageContent(String value) {

    public static final int MAX_LENGTH = 1000;
    // ADR-052: 줄바꿈은 허용하고, DB마다 저장 결과가 달라지는 NUL·짝 없는 서로게이트는 막는다.
    public static final String ALLOWED = "[^\\x00\\p{Cs}]*";

    public MessageContent {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH
                || !value.matches(ALLOWED)) {
            throw new IllegalArgumentException("메시지는 1~1000자이고 NUL·짝 없는 서로게이트를 포함할 수 없다");
        }
    }
}
