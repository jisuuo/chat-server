package jissuo.chat.experiment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import jissuo.chat.experiment.support.ExperimentResults;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import tools.jackson.databind.json.JsonMapper;

/** F17: 앱 1대가 내려갈 때 고정 1초와 전체 지터의 재접속·복구 조회를 비교한다. */
@Tag("experiment")
@TestMethodOrder(OrderAnnotation.class)
class ReconnectStormExperiment {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient METRICS_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final String HEADER = "phase,clients,initialApp1,initialApp2,firstCloseToAllOpenMs,"
            + "peakAttemptsPerSec,peakOpenedPerSec,peakFailedPerSec,failures,baselineP50Ms,baselineP99Ms,"
            + "recoveries,recoveryFailures,recoveryP50Ms,recoveryP99Ms,p99Ratio,app2PendingMax,"
            + "after60App1,after60App2,nginxWorkerConnectionErrors";

    @BeforeAll
    static void clusterIsRunning() {
        ClusterControl.assumeRunning();
    }

    @Test
    @Order(1)
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void smoke_100_connections() throws Exception {
        try (Run run = new Run(100)) {
            run.connectAll();
            int app1 = metric(ClusterControl.APP1, "chat_ws_sessions");
            int app2 = metric(ClusterControl.APP2, "chat_ws_sessions");
            ExperimentResults.record("cluster-f17-smoke", "clients,app1,app2,opened",
                    "100," + app1 + "," + app2 + "," + run.openCount());
            assertEquals(100, app1 + app2);
            assertEquals(100, run.openCount());
        }
        waitUntil(() -> sessions() == 0, Duration.ofSeconds(30), "smoke 연결 정리");
    }

    @Test
    @Order(2)
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void storm_1000_connections() throws Exception {
        assertEquals("bench", System.getenv("CLUSTER_PROFILE"),
                "재시작 실험은 CLUSTER_PROFILE=bench 환경에서 실행해야 한다");
        for (String phase : List.of("fixed-graceful", "jitter-graceful", "fixed-hard", "jitter-hard")) {
            String policyName = phase.startsWith("fixed") ? "fixed" : "jitter";
            String restartKind = phase.endsWith("graceful") ? "graceful" : "hard";
            // 이전 재시작이 nginx의 upstream 선택에 남긴 영향을 제거해 두 조건의 시작점을 맞춘다.
            ClusterControl.useNginx("chat");
            try (Run run = new Run(1_000, policyName)) {
                run.connectAll();
                int initialApp1 = metric(ClusterControl.APP1, "chat_ws_sessions");
                int initialApp2 = metric(ClusterControl.APP2, "chat_ws_sessions");
                assertEquals(1_000, initialApp1 + initialApp2, "측정 전 연결 수");
                assertTrue(initialApp1 >= 400 && initialApp1 <= 600,
                        "app1 초기 연결이 400~600 범위를 벗어나 조건 비교가 어렵다: " + initialApp1);
                List<Long> baseline = run.baseline(100);
                long baselineP50 = percentile(baseline, 0.50);
                long baselineP99 = percentile(baseline, 0.99);
                Instant since = Instant.now();
                long started = System.nanoTime();
                List<Integer> pending = new ArrayList<>();
                run.timer.scheduleAtFixedRate(() -> {
                    int value = metric(ClusterControl.APP2, "hikaricp_connections_pending");
                    synchronized (pending) { pending.add(value); }
                }, 0, 1, TimeUnit.SECONDS);

                if (restartKind.equals("graceful")) {
                    ClusterControl.restart("app1");
                } else {
                    ClusterControl.kill("app1");
                    ClusterControl.start("app1");
                }
                waitUntil(() -> run.openCount() == 1_000 && sessions() == 1_000,
                        Duration.ofMinutes(2), phase + " 재연결");
                long allOpen = System.nanoTime();
                waitUntil(() -> run.recoveryCountSince(started) >= initialApp1,
                        Duration.ofSeconds(30), phase + " 복구 조회");
                // F54: 살아 있는 서버에 붙은 긴 연결은 앱 1 복구 후에도 이동하지 않는다.
                long after60 = started + TimeUnit.SECONDS.toNanos(60);
                long remaining = after60 - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                int after60App1 = metric(ClusterControl.APP1, "chat_ws_sessions");
                int after60App2 = metric(ClusterControl.APP2, "chat_ws_sessions");
                List<StormClient.Event> events = run.eventsSince(started);
                List<Long> recovery = recoveryDurations(events);
                long recoveryFailures = events.stream().filter(event -> event.type().equals("RECOVERED")
                        && !event.detail().startsWith("200:")).count();
                Map<String, Long> failures = new HashMap<>();
                events.stream().filter(event -> event.type().equals("FAILED"))
                        .forEach(event -> failures.merge(event.detail(), 1L, Long::sum));
                int pendingMax;
                synchronized (pending) { pendingMax = pending.stream().mapToInt(Integer::intValue).max().orElse(-1); }
                long firstClose = events.stream().filter(event -> event.type().equals("CLOSED"))
                        .mapToLong(StormClient.Event::nanos).min().orElse(started);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(allOpen - firstClose);
                long recoverP50 = percentile(recovery, 0.50);
                long recoverP99 = percentile(recovery, 0.99);
                long nginxErrors = ClusterControl.logs("nginx", since).lines()
                        .filter(line -> line.contains("worker_connections are not enough")).count();
                ExperimentResults.record("cluster-f17-storm", HEADER, String.join(",",
                        phase, "1000", Integer.toString(initialApp1), Integer.toString(initialApp2),
                        Long.toString(elapsedMs), Integer.toString(peak(events, "ATTEMPT", started)),
                        Integer.toString(peak(events, "OPENED", started)),
                        Integer.toString(peak(events, "FAILED", started)), csv(failures),
                        Long.toString(baselineP50), Long.toString(baselineP99), Integer.toString(recovery.size()),
                        Long.toString(recoveryFailures), Long.toString(recoverP50), Long.toString(recoverP99),
                        baselineP99 == 0 ? "NaN" : Double.toString((double) recoverP99 / baselineP99),
                        Integer.toString(pendingMax), Integer.toString(after60App1), Integer.toString(after60App2),
                        Long.toString(nginxErrors)));
                ExperimentResults.record("cluster-f17-policy-comparison",
                        "policy,condition,initialApp1,initialApp2,peakAttempts1s,peakAttempts100ms,"
                                + "allOpenMs,recoveryP50Ms,recoveryP99Ms,recoveryFailures,after60App1,after60App2",
                        String.join(",", policyName, restartKind, Integer.toString(initialApp1),
                                Integer.toString(initialApp2), Integer.toString(peak(events, "ATTEMPT", started)),
                                Integer.toString(peakInMillis(events, "ATTEMPT", started, 100)),
                                Long.toString(elapsedMs), Long.toString(recoverP50), Long.toString(recoverP99),
                                Long.toString(recoveryFailures), Integer.toString(after60App1), Integer.toString(after60App2)));
                for (var entry : failures.entrySet()) {
                    ExperimentResults.record("cluster-f17-failures", "phase,message,count",
                            phase + "," + csv(entry.getKey()) + "," + entry.getValue());
                }
                for (int second = 0; second <= 60; second++) {
                    ExperimentResults.record("cluster-f17-seconds", "phase,second,attempts,opened,failed",
                            phase + "," + second + "," + count(events, "ATTEMPT", started, second)
                                    + "," + count(events, "OPENED", started, second)
                                    + "," + count(events, "FAILED", started, second));
                }
                assertTrue(recovery.size() > 0, "재연결 후 복구 조회가 실행돼야 한다");
            }
            waitUntil(() -> sessions() == 0, Duration.ofSeconds(60), phase + " 연결 정리");
        }
    }

    private static final class Run implements AutoCloseable {
        private final int size;
        private final ClusterHttp http = new ClusterHttp(ClusterControl.NGINX, JSON);
        private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(32);
        private final List<StormClient> clients = new ArrayList<>();

        Run(int size) throws Exception {
            this(size, "fixed");
        }

        Run(int size, String policyName) throws Exception {
            this.size = size;
            String prefix = "storm-" + System.nanoTime();
            for (int roomIndex = 0; roomIndex < size / 10; roomIndex++) {
                long owner = http.createUser(prefix + "-" + roomIndex + "-0");
                long room = http.createRoom(owner, prefix + "-" + roomIndex);
                clients.add(new StormClient(owner, room, policy(policyName, owner), http, timer));
                for (int memberIndex = 1; memberIndex < 10; memberIndex++) {
                    long member = http.createUser(prefix + "-" + roomIndex + "-" + memberIndex);
                    http.join(member, room);
                    clients.add(new StormClient(member, room, policy(policyName, member), http, timer));
                }
            }
        }

        private static ReconnectPolicy policy(String name, long userId) {
            return name.equals("jitter")
                    ? new FullJitter(Duration.ofSeconds(1), Duration.ofSeconds(30),
                            new SplittableRandom(System.nanoTime() ^ userId))
                    : new Fixed(Duration.ofSeconds(1));
        }

        void connectAll() throws Exception {
            clients.forEach(StormClient::start);
            waitUntil(() -> openCount() == size && sessions() == size,
                    Duration.ofMinutes(2), size + "개 최초 연결");
            waitUntil(() -> recoveryCountSince(0) >= size, Duration.ofSeconds(30), "최초 조회");
        }

        int openCount() {
            return (int) clients.stream().filter(StormClient::isOpen).count();
        }

        int recoveryCountSince(long nanos) {
            return (int) eventsSince(nanos).stream().filter(event -> event.type().equals("RECOVERED")).count();
        }

        List<Long> baseline(int sample) throws Exception {
            List<Long> times = new ArrayList<>();
            for (int i = 0; i < sample; i++) {
                long start = System.nanoTime();
                int status = http.latest(clients.get(i).userId(), clients.get(i).roomId()).statusCode();
                assertEquals(200, status);
                times.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
            return times;
        }

        List<StormClient.Event> eventsSince(long nanos) {
            return clients.stream().flatMap(client -> client.events().stream())
                    .filter(event -> event.nanos() >= nanos).toList();
        }

        @Override
        public void close() throws Exception {
            for (StormClient client : clients) client.close();
            timer.shutdownNow();
        }
    }

    private static int sessions() {
        int first = metric(ClusterControl.APP1, "chat_ws_sessions");
        int second = metric(ClusterControl.APP2, "chat_ws_sessions");
        return first < 0 || second < 0 ? -1 : first + second;
    }

    private static int metric(int port, String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> response = METRICS_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body().lines().filter(line -> line.startsWith(name + "{"))
                    .mapToInt(line -> (int) Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                    .sum();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError(description + " 시간 초과");
    }

    private static List<Long> recoveryDurations(List<StormClient.Event> events) {
        return events.stream().filter(event -> event.type().equals("RECOVERED")
                        && event.detail().startsWith("200:"))
                .map(event -> Long.parseLong(event.detail().split(":")[1])).toList();
    }

    private static long percentile(List<Long> values, double fraction) {
        if (values.isEmpty()) return -1;
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        return sorted[Math.max(0, (int) Math.ceil(fraction * sorted.length) - 1)];
    }

    private static int peak(List<StormClient.Event> events, String type, long started) {
        Map<Long, Integer> counts = new HashMap<>();
        for (StormClient.Event event : events) {
            if (event.type().equals(type)) {
                long second = TimeUnit.NANOSECONDS.toSeconds(event.nanos() - started);
                counts.merge(second, 1, Integer::sum);
            }
        }
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static int peakInMillis(List<StormClient.Event> events, String type, long started, int widthMs) {
        Map<Long, Integer> counts = new HashMap<>();
        long width = TimeUnit.MILLISECONDS.toNanos(widthMs);
        for (StormClient.Event event : events) {
            if (event.type().equals(type)) {
                counts.merge((event.nanos() - started) / width, 1, Integer::sum);
            }
        }
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static long count(List<StormClient.Event> events, String type, long started, int second) {
        return events.stream().filter(event -> event.type().equals(type)
                && TimeUnit.NANOSECONDS.toSeconds(event.nanos() - started) == second).count();
    }

    private static String csv(Object value) {
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }
}
