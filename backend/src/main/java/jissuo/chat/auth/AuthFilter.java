package jissuo.chat.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.common.ChatException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-User-Id";
    static final String ATTRIBUTE = AuthUser.class.getName();

    private final Authenticator authenticator;
    private final ApplicationEventPublisher events;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuthFilter(Authenticator authenticator, ApplicationEventPublisher events, JsonMapper jsonMapper, Clock clock) {
        this.authenticator = authenticator;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    // 계획 1 세부 3: 개발용 사용자 생성은 사용자를 만들기 전이라 보낼 id가 없다
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathOf(request);
        return !path.startsWith("/api/") || path.startsWith("/api/dev/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String credential = request.getHeader(HEADER);
        AuthUser user;
        try {
            user = authenticator.authenticate(credential);
        } catch (ChatException e) {
            events.publishEvent(new AuthenticationFailedEvent(credential, pathOf(request), clock.instant()));
            // 필터에서 던진 예외는 DispatcherServlet 밖이라 @RestControllerAdvice까지 가지 않으므로 응답을 직접 쓴다
            response.setStatus(e.errorCode().status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            jsonMapper.writeValue(response.getOutputStream(), ApiResponse.fail(e.errorCode()));
            return;
        }
        request.setAttribute(ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private static String pathOf(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
