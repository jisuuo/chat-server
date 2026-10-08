package jissuo.chat.auth;

import java.time.Clock;
import java.util.Map;
import jissuo.chat.common.ChatException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 브라우저 WebSocket API는 헤더를 붙일 수 없어 쿼리에서 꺼낸다. 판별은 HTTP와 같은 Authenticator가 한다
 * (계획 7 결정 D1, ADR-005·006과 같은 신뢰 수준). 핸드셰이크에서 한 번만 검사하고 프레임마다 다시 보지 않는다.
 */
@Component
public class QueryUserIdHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PARAMETER = "userId";
    public static final String ATTRIBUTE = AuthUser.class.getName();

    private final Authenticator authenticator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public QueryUserIdHandshakeInterceptor(Authenticator authenticator, ApplicationEventPublisher events, Clock clock) {
        this.authenticator = authenticator;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 서블릿이 디코딩한 값을 쓴다. 헤더와 같은 문자열 규칙(ADR-046)으로 판별되게 하려는 것이다
        String credential = ((ServletServerHttpRequest) request).getServletRequest().getParameter(PARAMETER);
        try {
            attributes.put(ATTRIBUTE, authenticator.authenticate(credential));
            return true;
        } catch (ChatException e) {
            events.publishEvent(new AuthenticationFailedEvent(credential, request.getURI().getPath(), clock.instant()));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
