package jissuo.chat.message.domain;

/** after는 폴링, before는 이전 대화 조회, latest는 최신 대화 조회다 (ADR-017). */
public record MessageCursor(Direction direction, Long id) {

    public enum Direction { LATEST, AFTER, BEFORE }

    public MessageCursor {
        if (direction == null || (direction == Direction.LATEST) != (id == null) || (id != null && id < 0)) {
            throw new IllegalArgumentException("메시지 커서가 올바르지 않다");
        }
    }

    public static MessageCursor latest() {
        return new MessageCursor(Direction.LATEST, null);
    }

    public static MessageCursor after(long id) {
        return new MessageCursor(Direction.AFTER, id);
    }

    public static MessageCursor before(long id) {
        return new MessageCursor(Direction.BEFORE, id);
    }

    public static MessageCursor of(Long after, Long before) {
        if (after != null && before != null) {
            throw new IllegalArgumentException("after와 before를 함께 지정할 수 없다");
        }
        if (after != null) {
            return after(after);
        }
        return before == null ? latest() : before(before);
    }
}
