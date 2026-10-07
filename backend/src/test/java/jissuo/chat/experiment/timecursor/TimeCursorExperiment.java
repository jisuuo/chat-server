package jissuo.chat.experiment.timecursor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jissuo.chat.experiment.support.Concurrently;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.application.RoomService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("experiment")
abstract class TimeCursorExperiment {
    @Autowired MessageService service;
    @Autowired MessageRepository messages;
    @Autowired RoomService rooms;
    @Autowired JdbcClient jdbc;
    abstract String condition();

    @Test void sameTimestampAndPageBoundary() {
        var fixture = new ExperimentFixtures(jdbc);
        long user = fixture.user("f2");
        long room = rooms.create(user, "f2").id();
        Instant t = Instant.now().plusSeconds(1);
        long a = messages.save(room, user, new MessageContent("a"), t).id();
        long b = messages.save(room, user, new MessageContent("b"), t).id();
        long c = messages.save(room, user, new MessageContent("c"), t).id();
        var strict = new TimeCursorPoller(jdbc, room, false, 2);
        var inclusive = new TimeCursorPoller(jdbc, room, true, 2);
        List<Long> strict1 = strict.poll(), strict2 = strict.poll();
        List<Long> inclusive1 = inclusive.poll(), inclusive2 = inclusive.poll();
        var id1 = service.read(user, room, MessageCursor.after(0), 2);
        var id2 = service.read(user, room, MessageCursor.after(b), 2);
        ExperimentResults.record("f2-deterministic", "condition,strict_first,strict_second,inclusive_second,id_second",
                condition() + "," + csvIds(strict1) + "," + csvIds(strict2) + "," + csvIds(inclusive2)
                        + "," + csvIds(id2.messages().stream().map(m -> m.id()).toList()));
        assertThat(strict1).containsExactly(a, b);
        assertThat(strict2).isEmpty();
        assertThat(inclusive1).containsExactly(a, b);
        assertThat(inclusive2).containsExactly(a, b);
        assertThat(id1.messages()).extracting(m -> m.id()).containsExactly(a, b);
        assertThat(id2.messages()).extracting(m -> m.id()).containsExactly(c);
        long d = messages.save(room, user, new MessageContent("d"), t).id();
        assertThat(strict.poll()).isEmpty();
        assertThat(service.read(user, room, MessageCursor.after(c), 2).messages())
                .extracting(m -> m.id()).containsExactly(d);
    }

    @ParameterizedTest @ValueSource(ints = {1, 10, 50})
    void naturalFrequency(int writers) throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        long reader = fixture.user("f2reader");
        long room = rooms.create(reader, "f2freq").id();
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            long user = fixture.user("f2writer"); rooms.join(user, room); users.add(user);
        }
        var idReceived = new HashSet<Long>();
        var gtReceived = new HashSet<Long>();
        var geReceived = new HashSet<Long>();
        var duplicates = new AtomicInteger();
        var gt = new TimeCursorPoller(jdbc, room, false, 100);
        var ge = new TimeCursorPoller(jdbc, room, true, 100);
        var running = new AtomicBoolean(true);
        var poller = new Thread(() -> {
            long cursor = 0;
            while (running.get()) {
                var page = service.read(reader, room, MessageCursor.after(cursor), 100);
                for (var m : page.messages()) { idReceived.add(m.id()); cursor = m.id(); }
                gtReceived.addAll(gt.poll());
                for (long id : ge.poll()) if (!geReceived.add(id)) duplicates.incrementAndGet();
            }
        });
        poller.start();
        var sent = new AtomicInteger();
        var assign = new AtomicInteger();
        var errors = Concurrently.run(writers, Duration.ofSeconds(5), () -> {
            service.send(users.get(Math.floorMod(assign.getAndIncrement(), users.size())), room, "m");
            sent.incrementAndGet();
        });
        running.set(false); poller.join();
        long cursor = idReceived.stream().mapToLong(Long::longValue).max().orElse(0);
        while (true) {
            var page = service.read(reader, room, MessageCursor.after(cursor), 100);
            if (page.messages().isEmpty()) break;
            for (var m : page.messages()) { idReceived.add(m.id()); cursor = m.id(); }
        }
        gtReceived.addAll(gt.poll());
        for (long id : ge.poll()) if (!geReceived.add(id)) duplicates.incrementAndGet();
        var all = new HashSet<>(fixture.messageIds(room));
        ExperimentResults.record("f2-frequency", "condition,writers,sent,id_missed,time_gt_missed,time_ge_missed,time_ge_duplicates",
                condition() + "," + writers + "," + sent.get() + "," + difference(all, idReceived)
                        + "," + difference(all, gtReceived) + "," + difference(all, geReceived) + "," + duplicates.get());
        assertThat(errors).isEmpty();
        assertThat(all).hasSize(sent.get());
    }
    private static int difference(HashSet<Long> all, HashSet<Long> seen) {
        var copy = new HashSet<>(all); copy.removeAll(seen); return copy.size();
    }
    private static String csvIds(List<Long> ids) { return ids.toString().replace(", ", ";"); }
}
