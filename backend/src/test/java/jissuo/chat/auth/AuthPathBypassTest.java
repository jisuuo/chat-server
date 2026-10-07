package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jissuo.chat.common.ApiResponse;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F24, ADR-047: 필터는 정규화하지 않은 경로로 /api/dev/ 제외를 판단한다. 지금은 Spring MVC도 같은 원래 경로로 컨트롤러를 찾아서
 * 우회한 요청이 404가 되지만, 경로 매칭 방식이 바뀌면 깨지므로 지켜 본다.
 * MockMvc는 Tomcat의 경로 처리를 거치지 않아 재현할 수 없어서 실제 서버를 띄운다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(AuthPathBypassTest.ProbeController.class)
class AuthPathBypassTest {

    // java.net.http.HttpClient는 curl과 달리 ".."를 정리하지 않고 그대로 보낸다 (측정으로 확인)
    final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void 조작하지_않은_경로는_필터가_막는다() throws Exception {
        // 아래 404가 "필터가 아니라 다른 이유로 막혔다"는 것을 보이기 위한 대조군
        assertThat(statusOf("/api/probe/open")).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/dev/../probe/open", "/api/dev/../probe/me",
            "/api/dev/%2e%2e/probe/open", "/api/dev/%2E%2E/probe/me",
            "/api/dev/..;/probe/open"
    })
    void dev_경로로_필터를_건너뛰어도_컨트롤러에_닿지_않는다(String path) throws Exception {
        assertThat(statusOf(path)).isEqualTo(404);
    }

    private int statusOf(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe/me")
        ApiResponse<Long> me(@CurrentUser AuthUser user) {
            return ApiResponse.ok(user.id());
        }

        // @CurrentUser가 없어서 리졸버의 두 번째 방어도 없는 컨트롤러
        @GetMapping("/api/probe/open")
        ApiResponse<String> open() {
            return ApiResponse.ok("open");
        }
    }
}
