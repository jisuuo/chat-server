package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(ClientIpTest.ProbeController.class)
class ClientIpTest {

    final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void 헤더가_없으면_접속한_주소를_쓴다() throws Exception {
        assertThat(clientIp(null)).isEqualTo("127.0.0.1");
    }

    @Test
    void 내부_대역_프록시가_붙인_주소를_쓴다() throws Exception {
        assertThat(clientIp("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void 클라이언트가_앞에_끼워_넣은_주소는_믿지_않는다() throws Exception {
        assertThat(clientIp("1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    private String clientIp(String forwardedFor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/dev/probe/client-ip"));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/dev/probe/client-ip")
        String clientIp() {
            return MDC.get(RequestLogContextFilter.CLIENT_IP);
        }
    }
}
