package jissuo.chat.experiment.cluster;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.springframework.web.socket.WebSocketHttpHeaders;

final class ClusterControl {

    static final int NGINX = 18090;
    static final int APP1 = 18081;
    static final int APP2 = 18082;
    static final String ORIGIN = "http://localhost:18090";
    private static final Path COMPOSE = Path.of("..", "infra", "compose.cluster.yml").toAbsolutePath().normalize();

    private ClusterControl() {}

    static void assumeRunning() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(ORIGIN + "/api/users"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            Assumptions.assumeTrue(status == 401 || status == 200,
                    "클러스터가 떠 있지 않다: infra/cluster/README.md (HTTP " + status + ")");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Assumptions.abort("클러스터가 떠 있지 않다: infra/cluster/README.md (" + e + ")");
        }
    }

    static void useNginx(String conf) {
        run(List.of("up", "-d", "--force-recreate", "--wait", "nginx"), conf);
    }

    static void restart(String service) {
        run(List.of("restart", service), null);
    }

    static void kill(String service) {
        run(List.of("kill", service), null);
    }

    static void start(String service) {
        run(List.of("up", "-d", "--wait", service), null);
    }

    static String logs(String service, Instant since) {
        return run(List.of("logs", "--since", since.toString(), service), null);
    }

    static WebSocketHttpHeaders browserHeaders() {
        // ADR-155: Java 클라이언트도 브라우저와 같은 origin 검사 경로를 통과시킨다.
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(ORIGIN);
        return headers;
    }

    private static String run(List<String> arguments, String nginxConf) {
        List<String> command = new ArrayList<>(List.of("docker", "compose", "-f", COMPOSE.toString()));
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
        if (nginxConf != null) {
            processBuilder.environment().put("NGINX_CONF", nginxConf);
        }
        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int status = process.waitFor();
            if (status != 0) {
                throw new IllegalStateException("docker compose 종료 코드 " + status + ": " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("docker compose 실행 실패: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("docker compose 대기 중 중단: " + command, e);
        }
    }
}
