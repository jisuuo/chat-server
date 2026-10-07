package jissuo.chat.message.domain;

/** 메시지 길이는 두 DB의 VARCHAR(1000)과 같은 코드 포인트 기준으로 센다 (ADR-048). */
public record MessageContent(String value) {

    public static final int MAX_LENGTH = 1000;

    public MessageContent {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > MAX_LENGTH
                || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("메시지는 1~1000자이고 NUL 문자를 포함할 수 없다");
        }
    }
}
