package jissuo.chat.experiment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F8: 최소 nginx 설정에서 브라우저와 같은 Origin으로 핸드셰이크를 시도한다. */
@Tag("experiment")
class ProxyHandshakeExperiment {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern WS_ACCESS = Pattern.compile(
            "\"GET /ws\\?userId=\\d+ HTTP/[^\"]+\" (\\d{3}) upstream=([^ ]+)");
    private static final String HANDSHAKE_HEADER = "conf,attempt,result,status,upstream";

    @BeforeAll
    static void clusterIsRunning() {
        ClusterControl.assumeRunning();
    }

    @AfterEach
    void restoreNginx() {
        // F8: 대조 설정으로 끝난 실험이 뒤따르는 클러스터 실험의 핸드셰이크를 깨지 않게 한다.
        ClusterControl.useNginx("chat");
    }

    @Test
    void naive_설정에서_핸드셰이크() throws Exception {
        assertHandshake("naive", 0, "403");
    }

    @Test
    void 설정별_핸드셰이크() throws Exception {
        for (String conf : List.of("naive", "f8-upgrade", "f8-host", "chat")) {
            assertHandshake(conf, "chat".equals(conf) ? 10 : 0, "chat".equals(conf) ? "101" : "403");
        }
    }

    private static void assertHandshake(String conf, int expectedSuccesses, String expectedStatus) throws Exception {
        ClusterControl.useNginx(conf);
        long userId = new ClusterHttp(ClusterControl.NGINX, JSON).createUser("f8-" + System.nanoTime());
        URI uri = URI.create("ws://localhost:" + ClusterControl.NGINX + "/ws?userId=" + userId);
        Instant started = Instant.now();
        List<String> results = new ArrayList<>();

        for (int attempt = 1; attempt <= 10; attempt++) {
            try (WsTestClient ignored = WsTestClient.connect(uri, ClusterControl.browserHeaders())) {
                results.add("success");
            } catch (Exception failure) {
                results.add("failure: " + failure);
            }
        }

        List<ProxyAccess> access = waitForAccess(userId, started, results.size());
        if (access.size() != results.size()) {
            throw new IllegalStateException("nginx /ws 접근 로그 " + access.size()
                    + "건, 핸드셰이크 시도 " + results.size() + "건");
        }
        for (int i = 0; i < results.size(); i++) {
            ProxyAccess proxy = access.get(i);
            ExperimentResults.record("cluster-f8-handshake", HANDSHAKE_HEADER,
                    String.join(",", conf, Integer.toString(i + 1), csv(results.get(i)),
                            proxy.status(), csv(proxy.upstream())));
        }
        int successes = (int) results.stream().filter("success"::equals).count();
        System.out.println("[experiment] " + conf + " nginx /ws access rows=" + access.size()
                + ", successes=" + successes);
        assertEquals(expectedSuccesses, successes, conf + " WebSocket 핸드셰이크 성공 횟수");
        assertEquals(10, access.stream().filter(proxy -> expectedStatus.equals(proxy.status())).count(),
                conf + " nginx /ws 상태 코드");
    }

    private static List<ProxyAccess> waitForAccess(long userId, Instant started, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<ProxyAccess> access;
        do {
            access = ClusterControl.logs("nginx", started).lines()
                    .filter(line -> line.contains("\"GET /ws?userId=" + userId + " HTTP/"))
                    .map(WS_ACCESS::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> new ProxyAccess(matcher.group(1), matcher.group(2)))
                    .toList();
            if (access.size() == count) {
                return access;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return access;
    }

    @Test
    void naive_설정의_REST_clientIp() throws Exception {
        ClusterControl.useNginx("naive");
        measureClientIp("naive", null);
    }

    @Test
    void chat_설정의_REST_clientIp() throws Exception {
        ClusterControl.useNginx("chat");
        String observed = measureClientIp("chat", null);
        String forged = measureClientIp("chat", "1.2.3.4");
        assertEquals(observed, forged, "nginx가 받은 X-Forwarded-For로 접근 로그 주소를 바꾸면 안 된다");
        assertNotEquals("1.2.3.4", forged, "요청자가 제공한 X-Forwarded-For를 클라이언트 IP로 기록하면 안 된다");
    }

    private static String measureClientIp(String conf, String suppliedForwardedFor) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(ClusterControl.ORIGIN + "/api/users"))
                .timeout(Duration.ofSeconds(2)).GET();
        if (suppliedForwardedFor != null) {
            requestBuilder.header("X-Forwarded-For", suppliedForwardedFor);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        String clientIp = accessIp(requestId);
        ExperimentResults.record("cluster-f8-client-ip", "conf,status,upstream,requestId,clientIp",
                String.join(",", suppliedForwardedFor == null ? conf : conf + "-spoofed",
                        Integer.toString(response.statusCode()), csv(ClusterHttp.upstream(response)), requestId, clientIp));
        return clientIp;
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void chat_설정의_유휴_유지와_5초_대조() throws Exception {
        ClusterControl.useNginx("chat");
        long userId = new ClusterHttp(ClusterControl.NGINX, JSON).createUser("idle-" + System.nanoTime());
        URI uri = URI.create("ws://localhost:" + ClusterControl.NGINX + "/ws?userId=" + userId);
        try (WsTestClient client = WsTestClient.connect(uri, ClusterControl.browserHeaders())) {
            for (int elapsed = 60; elapsed <= 180; elapsed += 60) {
                Thread.sleep(60_000);
                ExperimentResults.record("cluster-f8-idle", "conf,elapsedSeconds,isOpen,closedEvent",
                        String.join(",", "chat", Integer.toString(elapsed),
                                Boolean.toString(client.isOpen()), Boolean.toString(client.closedEventSeen())));
                assertTrue(client.isOpen(), "chat WebSocket이 " + elapsed + "초 전에 닫혔다");
                assertFalse(client.closedEventSeen(), "chat WebSocket에 종료 이벤트가 왔다");
            }
        }

        ClusterControl.useNginx("f8-timeout5");
        long controlUser = new ClusterHttp(ClusterControl.NGINX, JSON).createUser("timeout-" + System.nanoTime());
        URI controlUri = URI.create("ws://localhost:" + ClusterControl.NGINX + "/ws?userId=" + controlUser);
        try (WsTestClient client = WsTestClient.connect(controlUri, ClusterControl.browserHeaders())) {
            long started = System.nanoTime();
            long deadline = started + TimeUnit.SECONDS.toNanos(20);
            while (!client.closedEventSeen() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
            ExperimentResults.record("cluster-f8-idle", "conf,elapsedSeconds,isOpen,closedEvent",
                    String.join(",", "f8-timeout5", Double.toString(elapsed),
                            Boolean.toString(client.isOpen()), Boolean.toString(client.closedEventSeen())));
            assertTrue(client.closedEventSeen(), "5초 유휴 대조 WebSocket이 20초 안에 닫히지 않았다");
            assertFalse(client.isOpen(), "종료 이벤트 뒤에도 5초 유휴 대조 WebSocket이 열려 있다");
        }
    }

    private static String accessIp(String requestId) throws Exception {
        for (String service : List.of("app1", "app2")) {
            Path log = Path.of("logs", "cluster", service, "app.json");
            try (var lines = Files.lines(log)) {
                for (String line : lines.filter(value -> value.contains("\"requestId\":\"" + requestId + "\"")).toList()) {
                    JsonNode entry = JSON.readTree(line);
                    if ("ACCESS".equals(entry.at("/log/logger").asText())) {
                        return entry.path("clientIp").asText();
                    }
                }
            }
        }
        throw new IllegalStateException("ACCESS 로그에서 requestId를 찾지 못함: " + requestId);
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"").replace('\n', ' ').replace('\r', ' ') + "\"";
    }

    private record ProxyAccess(String status, String upstream) {}
}
