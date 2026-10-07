package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLogContextFilterTest {

    final RequestLogContextFilter filter = new RequestLogContextFilter();
    final ListAppender<ILoggingEvent> access = new ListAppender<>();
    Level previousLevel;

    @BeforeEach
    void attach() {
        previousLevel = accessLogger().getLevel();
        accessLogger().setLevel(Level.INFO);
        access.start();
        accessLogger().addAppender(access);
    }

    @AfterEach
    void detach() {
        accessLogger().detachAppender(access);
        accessLogger().setLevel(previousLevel);
    }

    @Test
    void 요청이_끝나면_접근_로그_한_줄을_사용자_id와_함께_남기고_MDC를_비운다() throws Exception {
        FilterChain chain = (req, res) -> {
            MDC.put(RequestLogContextFilter.USER_ID, "7");
            ((MockHttpServletResponse) res).setStatus(201);
        };
        var request = new MockHttpServletRequest("POST", "/api/rooms/3/messages");
        request.setQueryString("after=10");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(access.list).hasSize(1);
        ILoggingEvent line = access.list.getFirst();
        assertThat(fields(line)).containsEntry("method", "POST").containsEntry("path", "/api/rooms/3/messages")
                .containsEntry("query", "after=10").containsEntry("status", 201).containsKey("durationMs");
        assertThat(line.getMDCPropertyMap()).containsEntry(RequestLogContextFilter.USER_ID, "7")
                .containsKey(RequestLogContextFilter.REQUEST_ID);
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 밖으로_나온_예외는_500으로_적는다() {
        FilterChain chain = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest("GET", "/api/rooms"), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(fields(access.list.getFirst())).containsEntry("status", 500);
    }

    @Test
    void 액추에이터_요청은_접근_로그를_남기지_않는다() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(access.list).isEmpty();
    }

    @Test
    void 요청_동안_추적_ID와_IP를_MDC에_두고_응답_헤더로_돌려준다() throws Exception {
        Map<String, String> seen = new HashMap<>();
        FilterChain chain = (req, res) -> seen.putAll(MDC.getCopyOfContextMap());
        var request = new MockHttpServletRequest("GET", "/api/rooms");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader(RequestLogContextFilter.HEADER, "from-client");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(RequestLogContextFilter.HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(requestId).isNotEqualTo("from-client");
        assertThat(seen).containsEntry(RequestLogContextFilter.REQUEST_ID, requestId)
                .containsEntry(RequestLogContextFilter.CLIENT_IP, "203.0.113.7");
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
    }

    @Test
    void 처리_중_예외가_나도_MDC를_비운다() {
        FilterChain chain = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogContextFilter.CLIENT_IP)).isNull();
    }

    private static Logger accessLogger() {
        return (Logger) LoggerFactory.getLogger("ACCESS");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }
}
