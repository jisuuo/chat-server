package jissuo.chat.room.domain;

import java.time.Instant;

// ADR-036: domain은 Spring을 모른다. ErrorCode는 HttpStatus를 품고 있어 쓸 수 없으므로 코드 이름만 문자열로 받는다
public record AccessDeniedEvent(long roomId, long userId, String code, Instant at) {
}
