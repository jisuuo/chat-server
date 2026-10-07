package jissuo.chat.auth;

/**
 * HttpServletRequest가 아니라 문자열을 받는 이유: 통로(HTTP 헤더, Step 2의 WebSocket 핸드셰이크)마다 추출은 따로 하고
 * 판별은 이 한 곳에서 하기 위해서다 (ADR-006). JWT로 바꿀 때는 구현체만 바꾼다.
 */
public interface Authenticator {

    AuthUser authenticate(String credential);
}
