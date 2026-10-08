package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class QueryUserIdHandshakeInterceptorTest {

    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    final QueryUserIdHandshakeInterceptor interceptor =
            new QueryUserIdHandshakeInterceptor(new HeaderUserIdAuthenticator(), events, clock);

    @Test
    void 올바른_id면_AuthUser를_세션_속성에_둔다() {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        servlet.setParameter("userId", "7");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7));
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc", "0", "007", "+5"})
    void 없거나_형식이_틀리면_401로_거절하고_인증_실패_이벤트를_발행한다(String userId) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        if (userId != null) {
            servlet.setParameter("userId", userId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        verify(events).publishEvent(new AuthenticationFailedEvent(userId, "/ws", clock.instant()));
    }
}
