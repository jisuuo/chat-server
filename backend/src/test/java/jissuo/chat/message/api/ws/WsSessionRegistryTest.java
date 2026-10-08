package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.WebSocketSession;

class WsSessionRegistryTest {

    @Test
    @Timeout(20)
    void 같은_사용자의_동시_접속과_종료_중에도_각_탭을_찾을_수_있다() throws Exception {
        WsSessionRegistry registry = new WsSessionRegistry(new SimpleMeterRegistry());
        long userId = 7;
        int workers = 8;
        int rounds = 1_000;
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                jobs.add(pool.submit(() -> {
                    WebSocketSession tab = mock(WebSocketSession.class);
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        registry.add(userId, tab);
                        assertThat(registry.sessionsOf(userId)).contains(tab);
                        for (WebSocketSession openTab : registry.sessionsOf(userId)) {
                            assertThat(openTab).isNotNull();
                        }
                        registry.remove(userId, tab);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) {
                job.get();
            }
        }

        assertThat(registry.sessionsOf(userId)).isEmpty();
        assertThat(registry.count()).isZero();
    }
}
