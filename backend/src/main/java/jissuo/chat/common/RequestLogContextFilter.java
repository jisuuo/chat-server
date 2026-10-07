package jissuo.chat.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** AuthFilter의 401에도 추적 ID와 접근 로그가 붙도록 가장 먼저 실행한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_ID = "userId";

    private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 클라이언트가 검색 키를 고르지 못하도록 요청 헤더 대신 새 ID를 만든다.
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        // native 프록시 처리 후의 주소를 사용해 직접 보낸 X-Forwarded-For를 신뢰하지 않는다.
        MDC.put(CLIENT_IP, request.getRemoteAddr());
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            if (!request.getRequestURI().startsWith("/actuator/")) {
                ACCESS.atInfo().setMessage("access")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("query", request.getQueryString())
                        .addKeyValue("status", failed ? 500 : response.getStatus())
                        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .log();
            }
            MDC.remove(REQUEST_ID);
            MDC.remove(CLIENT_IP);
            MDC.remove(USER_ID);
        }
    }
}
