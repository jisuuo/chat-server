package jissuo.chat.room.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 방 목록에서 "이 방 다음부터"를 가리킨다. 정렬 기준이 (last_message_id, id)이므로 두 값을 함께 가진다.
 * 계획 1 세부 5: 문자열은 "{last_message_id 또는 -}:{id}"이다. 메시지가 없는 방은 "-"로 쓴다.
 */
public record RoomListCursor(Long lastMessageId, long id) {

    private static final Pattern FORMAT = Pattern.compile("(\\d+|-):(\\d+)");

    public static RoomListCursor parse(String value) {
        Matcher m = value == null ? null : FORMAT.matcher(value);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("방 목록 커서 형식이 아니다: " + value);
        }
        Long lastMessageId = m.group(1).equals("-") ? null : Long.parseLong(m.group(1));
        return new RoomListCursor(lastMessageId, Long.parseLong(m.group(2)));
    }

    public String format() {
        return (lastMessageId == null ? "-" : lastMessageId) + ":" + id;
    }
}
