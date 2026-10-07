package jissuo.chat.auth;

/**
 * Long 대신 값 객체로 두어 roomId와 userId가 뒤바뀌는 실수를 컴파일 단계에서 막는다 (ADR-006).
 * 닉네임 같은 표시용 정보는 넣지 않는다. 토큰이 만료되기 전에 값이 낡을 수 있기 때문이다.
 */
public record AuthUser(long id) {

    public AuthUser {
        if (id < 1) {
            throw new IllegalArgumentException("id는 1 이상이어야 한다: " + id);
        }
    }
}
