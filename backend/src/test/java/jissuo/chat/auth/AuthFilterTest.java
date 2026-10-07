package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import jissuo.chat.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(AuthFilterTest.TestController.class)
@Import({AuthFilterTest.TestController.class, HeaderUserIdAuthenticator.class, AuthFilterTest.FixedClock.class})
@RecordApplicationEvents
class AuthFilterTest {

    static final Instant NOW = Instant.parse("2026-10-07T01:02:03Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationEvents events;

    @Test
    void 올바른_헤더면_컨트롤러가_AuthUser를_받는다() throws Exception {
        mvc.perform(get("/api/test/me").header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success": true, "data": 42, "error": null}
                        """, JsonCompareMode.STRICT));

        assertThat(events.stream(AuthenticationFailedEvent.class)).isEmpty();
    }

    @Test
    void 인증_성공_동안_사용자_id가_MDC에_있고_요청_후에는_사라진다() throws Exception {
        mvc.perform(get("/api/test/mdc").header("X-User-Id", "42"))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));

        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    void 헤더가_없으면_401() throws Exception {
        mvc.perform(get("/api/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(unauthenticatedBody());
        assertThat(MDC.get("userId")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1"})
    void 헤더_형식이_틀리면_401(String header) throws Exception {
        mvc.perform(get("/api/test/me").header("X-User-Id", header))
                .andExpect(status().isUnauthorized())
                .andExpect(unauthenticatedBody());
    }

    @Test
    void 인증에_실패하면_원래_헤더_값과_경로와_시각을_담아_이벤트를_발행한다() throws Exception {
        mvc.perform(get("/api/test/me").header("X-User-Id", "abc"));

        assertThat(events.stream(AuthenticationFailedEvent.class))
                .containsExactly(new AuthenticationFailedEvent("abc", "/api/test/me", NOW));
    }

    @Test
    void 헤더가_없어서_실패하면_이벤트의_헤더_값은_null이다() throws Exception {
        mvc.perform(get("/api/test/me"));

        assertThat(events.stream(AuthenticationFailedEvent.class))
                .containsExactly(new AuthenticationFailedEvent(null, "/api/test/me", NOW));
    }

    @Test
    void dev_경로는_헤더_없이_통과한다() throws Exception {
        mvc.perform(get("/api/dev/test"))
                .andExpect(status().isOk());

        assertThat(events.stream(AuthenticationFailedEvent.class)).isEmpty();
    }

    @Test
    void api_밖의_경로는_헤더_없이_통과한다() throws Exception {
        mvc.perform(get("/outside/test"))
                .andExpect(status().isOk());
    }

    @Test
    void 필터를_거치지_않은_요청에서_CurrentUser를_받으려_하면_401() throws Exception {
        mvc.perform(get("/api/dev/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(unauthenticatedBody());
    }

    private static ResultMatcher unauthenticatedBody() {
        return content().json("""
                {"success": false, "data": null,
                 "error": {"code": "UNAUTHENTICATED", "message": "인증 정보가 없거나 올바르지 않습니다."}}
                """, JsonCompareMode.STRICT);
    }

    @TestConfiguration
    static class FixedClock {

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @RestController
    static class TestController {

        @GetMapping("/api/test/me")
        ApiResponse<Long> me(@CurrentUser AuthUser user) {
            return ApiResponse.ok(user.id());
        }

        @GetMapping("/api/dev/test")
        ApiResponse<String> dev() {
            return ApiResponse.ok("dev");
        }

        @GetMapping("/api/test/mdc")
        String mdcUserId() {
            return MDC.get("userId");
        }

        @GetMapping("/api/dev/me")
        ApiResponse<Long> devMe(@CurrentUser AuthUser user) {
            return ApiResponse.ok(user.id());
        }

        @GetMapping("/outside/test")
        ApiResponse<String> outside() {
            return ApiResponse.ok("outside");
        }
    }
}
