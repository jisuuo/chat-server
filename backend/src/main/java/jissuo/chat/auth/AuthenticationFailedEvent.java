package jissuo.chat.auth;

import java.time.Instant;

/**
 * 감사 로그(ADR-023)용. 수신하는 쪽은 계획 3에서 만든다.
 */
public record AuthenticationFailedEvent(String credential, String path, Instant at) {
}
