package jissuo.chat.experiment.ws;

import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import jissuo.chat.experiment.support.Concurrently;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.message.api.ws.WsSessionRegistry;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;

/** F3: 일반 HashMap·ArrayList 세션 저장소에서 동시 접속·종료·순회가 겹치면 예외·유실·남는 세션이 생기는지 잰다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class SessionRegistryExperiment {

    static final String HEADER = "mode,threads,seconds,operations,errors,errorTypes,lostAfterAdd,leftover,closeErrors,clientClosed";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired WsSessionRegistry registry;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(120)
    void 저장소를_직접_동시에_바꾸고_순회한다() throws Exception {
        // 실제 연결과 겹치지 않는 사용자 id. 스레드 0은 push처럼 순회만, 나머지는 자기 세션을 붙였다 뗀다
        WsSessionRegistry directRegistry = new WsSessionRegistry(new SimpleMeterRegistry());
        long userId = Long.MAX_VALUE - 1;
        int threads = 8;
        var roles = new AtomicInteger();
        ThreadLocal<Integer> role = ThreadLocal.withInitial(roles::getAndIncrement);
        ThreadLocal<WebSocketSession> own = ThreadLocal.withInitial(() -> mock(WebSocketSession.class));
        var operations = new AtomicLong();
        var lost = new AtomicLong();
        var errorCount = new AtomicLong();
        Map<String, LongAdder> errorCounts = new ConcurrentHashMap<>();

        Concurrently.run(threads, Duration.ofSeconds(10), () -> {
            try {
                if (role.get() == 0) {
                    for (WebSocketSession session : directRegistry.sessionsOf(userId)) {
                        session.getId();
                    }
                } else {
                    WebSocketSession session = own.get();
                    directRegistry.add(userId, session);
                    try {
                        if (!directRegistry.sessionsOf(userId).contains(session)) {
                            lost.incrementAndGet();
                        }
                    } finally {
                        directRegistry.remove(userId, session);
                    }
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                errorCounts.computeIfAbsent(e.getClass().getSimpleName(), ignored -> new LongAdder()).increment();
            } finally {
                operations.incrementAndGet();
                // 실패가 연속되어도 예외 객체와 세션 목록이 무제한 늘지 않게 부하를 제한한다.
                Thread.sleep(1);
            }
        });

        ExperimentResults.record("ws-session-registry-verified", HEADER, String.join(",", "direct",
                Integer.toString(threads), "10", Long.toString(operations.get()), Long.toString(errorCount.get()),
                errorCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + "=" + e.getValue().sum()).collect(Collectors.joining(";")),
                Long.toString(lost.get()), Integer.toString(directRegistry.sessionsOf(userId).size()), "0", "-"));
    }

    @Test
    @Timeout(180)
    void 같은_사용자가_동시에_많이_접속했다_끊는다() throws Exception {
        long userId = 4242;   // ADR-005: 형식만 맞으면 연결된다
        int clients = 200;
        double before = gauge();
        List<WsTestClient> connected = new ArrayList<>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newFixedThreadPool(32)) {
            List<Future<WsTestClient>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> WsTestClient.connect(port, userId)));
            }
            for (Future<WsTestClient> future : futures) {
                try {
                    connected.add(future.get());
                } catch (Exception e) {
                    errors.add(e);
                }
            }
        }
        Thread.sleep(2000);   // 서버 쪽 등록이 끝나기를 기다린다 (측정 조건, 고정)
        double afterConnect = gauge() - before;
        var closeErrors = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newFixedThreadPool(32)) {
            List<Future<Void>> closes = new ArrayList<>();
            for (WsTestClient client : connected) {
                closes.add(pool.submit(() -> {
                    client.close();
                    return null;
                }));
            }
            for (Future<Void> close : closes) {
                try {
                    close.get();
                } catch (Exception e) {
                    closeErrors.add(e);
                }
            }
        }
        Thread.sleep(5000);
        long clientClosed = connected.stream().filter(WsTestClient::closedEventSeen).count();
        ExperimentResults.record("ws-session-registry-verified", HEADER, String.join(",", "connections",
                Integer.toString(clients), "-", Integer.toString(connected.size()), Integer.toString(errors.size()),
                errorTypes(List.copyOf(errors)), Double.toString(clients - afterConnect),
                Double.toString(gauge() - before), Integer.toString(closeErrors.size()), Long.toString(clientClosed)));
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    private static String errorTypes(List<Throwable> errors) {
        Map<String, Long> types = errors.stream().collect(
                Collectors.groupingBy(e -> e.getClass().getSimpleName(), TreeMap::new, Collectors.counting()));
        return types.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
    }
}
