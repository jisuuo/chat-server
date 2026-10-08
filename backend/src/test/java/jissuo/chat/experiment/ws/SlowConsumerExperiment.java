package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** F4: 읽지 않는 수신자 한 명이 있을 때 보낸 사람의 응답과 다른 수신자의 도착이 함께 늦어지는지 잰다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class SlowConsumerExperiment {

    static final String ROWS = "phase,index,status,restMs,fastArrivalMs,expectedId,receivedId";
    static final String SUMMARY = "phase,messages,restP50Ms,restMaxMs,fastMaxMs,pushMeanMs,pushMaxMs,totalMeanMs,totalMaxMs,non201,fastMissing,storedDelta";
    // 한 번에 많이 쌓이도록 메시지당 약 3KB(한글 1000자, 최대 길이)
    static final String CONTENT = "가".repeat(1000);

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void 느린_수신자_유무로_전달_시간을_비교한다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long slow = fixtures.user("slow");
        long fast = fixtures.user("fast");
        long room = http.createRoom(sender, "slow-consumer");
        http.join(slow, room);
        http.join(fast, room);

        try (WsTestClient fastTab = WsTestClient.connect(port, fast)) {
            Thread.sleep(500);
            run("baseline", 50, http, sender, room, fastTab, false);
            try (StalledWsClient stalled = new StalledWsClient(port, slow)) {
                Thread.sleep(500);
                // 버퍼가 차서 응답이 2초를 넘으면 그 뒤 10건까지만 더 보내고 멈춘다
                run("stalled", 400, http, sender, room, fastTab, true);
            }
        }
    }

    private void run(String phase, int limit, ChatHttp http, long sender, long room, WsTestClient fastTab,
                     boolean stopAfterSlow) throws Exception {
        Snapshot push = Snapshot.of(timer("chat.delivery.stage", "stage", "push", "transport", "rest"));
        Snapshot total = Snapshot.of(timer("chat.delivery.total", "transport", "rest"));
        double droppedBefore = meters.counter("chat.ws.outbound.dropped", "reason", "queue_full").count();
        double failedBefore = meters.counter("chat.delivery.failed", "transport", "rest").count();
        List<Long> rest = new ArrayList<>();
        long fastMax = 0;
        int non201 = 0;
        int fastMissing = 0;
        int afterSlow = -1;
        long storedBefore = messageCount(room);
        for (int i = 0; i < limit && afterSlow != 0; i++) {
            long started = System.nanoTime();
            HttpResponse<String> response = http.send(sender, room, CONTENT);
            long restMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            WsTestClient.ReceivedFrame arrived = fastTab.pollReceived(java.time.Duration.ofSeconds(3));
            long fastMs = arrived == null ? -1
                    : TimeUnit.NANOSECONDS.toMillis(arrived.receivedNanos() - started);
            long expectedId = json.readTree(response.body()).at("/data/id").asLong();
            long receivedId = arrived == null ? -1 : json.readTree(arrived.payload()).at("/message/id").asLong();
            rest.add(restMs);
            fastMax = Math.max(fastMax, fastMs);
            if (expectedId != receivedId) {
                fastMissing++;
            }
            if (response.statusCode() != 201) {
                non201++;
            }
            ExperimentResults.record("ws-slow-consumer-timed-rows", ROWS, String.join(",", phase, Integer.toString(i),
                    Integer.toString(response.statusCode()), Long.toString(restMs), Long.toString(fastMs),
                    Long.toString(expectedId), Long.toString(receivedId)));
            if (stopAfterSlow && afterSlow < 0 && restMs > 2000) {
                afterSlow = 10;
            } else if (afterSlow > 0) {
                afterSlow--;
            }
        }
        rest.sort(null);
        ExperimentResults.record("ws-slow-consumer-timed", SUMMARY, String.join(",", phase, Integer.toString(rest.size()),
                Long.toString(rest.get(rest.size() / 2)), Long.toString(rest.getLast()), Long.toString(fastMax),
                push.meanSince(), push.max(), total.meanSince(), total.max(), Integer.toString(non201),
                Integer.toString(fastMissing), Long.toString(messageCount(room) - storedBefore)));
        ExperimentResults.record("ws-slow-consumer-remediation",
                "phase,messages,restMaxMs,fastMissing,queueDropped,deliveryFailed",
                String.join(",", phase, Integer.toString(rest.size()), Long.toString(rest.getLast()),
                        Integer.toString(fastMissing),
                        Long.toString(Math.round(meters.counter("chat.ws.outbound.dropped", "reason", "queue_full")
                                .count() - droppedBefore)),
                        Long.toString(Math.round(meters.counter("chat.delivery.failed", "transport", "rest")
                                .count() - failedBefore))));
    }

    private long messageCount(long room) {
        return jdbc.sql("SELECT COUNT(*) FROM messages WHERE room_id = :room")
                .param("room", room).query(Long.class).single();
    }

    private Timer timer(String name, String... tags) {
        return meters.timer(name, tags);
    }

    /** 단계 시간은 누적 타이머라서 구간 평균은 시작 시점과의 차이로 구한다. max는 Micrometer의 최근 구간 최댓값이다 */
    record Snapshot(Timer timer, long count, double totalMs) {
        static Snapshot of(Timer timer) {
            return new Snapshot(timer, timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
        }

        String meanSince() {
            long n = timer.count() - count;
            return n == 0 ? "-" : String.format("%.1f", (timer.totalTime(TimeUnit.MILLISECONDS) - totalMs) / n);
        }

        String max() {
            return String.format("%.1f", timer.max(TimeUnit.MILLISECONDS));
        }
    }
}
