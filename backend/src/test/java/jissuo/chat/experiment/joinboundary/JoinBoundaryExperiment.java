package jissuo.chat.experiment.joinboundary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.experiment.support.Ticks;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.application.RoomService;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.RoomRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("experiment")
abstract class JoinBoundaryExperiment {
    @Autowired JdbcClient jdbc;
    @Autowired RoomService rooms;
    @Autowired MessageService messages;
    @Autowired MessageRepository repository;
    @Autowired RoomRepository roomRepository;
    @Autowired MembershipRepository memberships;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager txManager;
    @Value("${chat.join-boundary}") String boundary;
    abstract String condition();
    private RoomService skewed(int ms) {
        return new RoomService(roomRepository, memberships, Clock.offset(Clock.systemUTC(), Duration.ofMillis(ms)), events);
    }
    private void join(RoomService service, long user, long room) {
        new TransactionTemplate(txManager).executeWithoutResult(status -> service.join(user, room));
    }
    private Set<Long> visible(long user, long room, long lo, long hi) {
        var seen = new HashSet<Long>();
        long cursor = Math.max(0, lo - 1);
        while (true) {
            var page = messages.read(user, room, MessageCursor.after(cursor), 100);
            if (page.messages().isEmpty()) break;
            for (Message m : page.messages()) {
                if (m.id() > hi) return seen;
                if (m.id() >= lo) seen.add(m.id());
                cursor = m.id();
            }
            if (cursor >= hi) break;
        }
        return seen;
    }
    @Test void deterministicClockSkewAndEqualMicrosecond() {
        var fixture = new ExperimentFixtures(jdbc);
        long owner = fixture.user("f18owner");
        long room = rooms.create(owner, "f18det").id();
        long old = messages.send(owner, room, "old").id();
        long behind = fixture.user("f18behind");
        join(skewed(-50), behind, room);
        boolean oldVisible = visible(behind, room, old, old).contains(old);
        long ahead = fixture.user("f18ahead");
        join(skewed(50), ahead, room);
        long fresh = messages.send(owner, room, "fresh").id();
        boolean freshVisible = visible(ahead, room, fresh, fresh).contains(fresh);
        long equalUser = fixture.user("f18equal");
        Instant fixed = Instant.now().plusSeconds(1);
        join(new RoomService(roomRepository, memberships, Clock.fixed(fixed, java.time.ZoneOffset.UTC), events), equalUser, room);
        long equalMessage = repository.save(room, owner, new MessageContent("equal"), fixed).id();
        boolean equalVisible = visible(equalUser, room, equalMessage, equalMessage).contains(equalMessage);
        ExperimentResults.record("f18-deterministic", "condition,old_visible,new_visible,equal_visible",
                condition() + "," + oldVisible + "," + freshVisible + "," + equalVisible);
        if (boundary.equals("id")) {
            assertThat(oldVisible).isFalse(); assertThat(freshVisible).isTrue(); assertThat(equalVisible).isTrue();
        } else {
            assertThat(oldVisible).isTrue(); assertThat(freshVisible).isFalse(); assertThat(equalVisible).isFalse();
        }
    }

    private record Joined(long user, Ticks.Span span, Set<Long> polled) {}
    @ParameterizedTest @CsvSource({"1,-50", "1,0", "1,50", "10,-50", "10,0", "10,50", "50,-50", "50,0", "50,50"})
    void naturalFrequency(int writers, int skewMs) throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        long owner = fixture.user("f18writer");
        long room = rooms.create(owner, "f18freq").id();
        List<Long> authors = new ArrayList<>(); authors.add(owner);
        for (int i = 1; i < writers; i++) {
            long author = fixture.user("f18writer"); rooms.join(author, room); authors.add(author);
        }
        var spans = new ConcurrentHashMap<Long, Ticks.Span>();
        var running = new AtomicBoolean(true);
        var writerErrors = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        var assign = new AtomicInteger();
        List<Joined> joined = new ArrayList<>();
        RoomService entrant = skewed(skewMs);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) pool.submit(() -> {
                long author = authors.get(Math.floorMod(assign.getAndIncrement(), authors.size()));
                while (running.get()) {
                    long start = Ticks.next();
                    try {
                        long id = messages.send(author, room, "m").id();
                        spans.put(id, new Ticks.Span(start, Ticks.next()));
                        Thread.sleep(1);
                    } catch (Throwable e) { writerErrors.add(e); running.set(false); }
                }
            });
            for (int i = 0; i < 300; i++) {
                long user = fixture.user("f18entrant");
                long start = Ticks.next();
                join(entrant, user, room);
                var span = new Ticks.Span(start, Ticks.next());
                var polled = new HashSet<Long>();
                for (var m : messages.read(user, room, MessageCursor.latest(), 100).messages()) polled.add(m.id());
                long cursor = polled.stream().mapToLong(Long::longValue).max().orElse(0);
                for (int p = 0; p < 10; p++) {
                    for (var m : messages.read(user, room, MessageCursor.after(cursor), 100).messages()) {
                        polled.add(m.id()); cursor = Math.max(cursor, m.id());
                    }
                    Thread.sleep(2);
                }
                joined.add(new Joined(user, span, polled));
            }
            running.set(false);
        }
        int judged = 0, concurrent = 0, leaks = 0, losses = 0, inconsistentUsers = 0, inconsistentIds = 0;
        Map<Long, Ticks.Span> ordered = new HashMap<>(spans);
        for (Joined j : joined) {
            long anchor = ordered.entrySet().stream().filter(e -> e.getValue().end() <= j.span().end())
                    .mapToLong(Map.Entry::getKey).max().orElse(0);
            long lo = Math.max(1, anchor - 500), hi = anchor + 500;
            Set<Long> listed = visible(j.user(), room, lo, hi);
            for (var entry : ordered.entrySet()) {
                long id = entry.getKey();
                if (id < lo || id > hi) continue;
                Ticks.Span m = entry.getValue();
                if (m.before(j.span())) { judged++; if (listed.contains(id)) leaks++; }
                else if (j.span().before(m)) { judged++; if (!listed.contains(id)) losses++; }
                else concurrent++;
            }
            if (!j.polled().isEmpty()) {
                long min = j.polled().stream().mapToLong(Long::longValue).min().orElse(0);
                long max = j.polled().stream().mapToLong(Long::longValue).max().orElse(0);
                var inRange = new HashSet<>(listed); inRange.removeIf(id -> id < min || id > max);
                var observed = new HashSet<>(j.polled()); observed.removeIf(id -> id < lo || id > hi);
                inRange.removeAll(observed); observed.removeAll(listed);
                int diff = inRange.size() + observed.size();
                if (diff > 0) { inconsistentUsers++; inconsistentIds += diff; }
            }
        }
        ExperimentResults.record("f18-frequency",
                "condition,writers,skew_ms,joins,judged,concurrent,leaks,losses,inconsistent_users,inconsistent_ids",
                condition() + "," + writers + "," + skewMs + "," + joined.size() + "," + judged
                        + "," + concurrent + "," + leaks + "," + losses + "," + inconsistentUsers + "," + inconsistentIds);
        assertThat(writerErrors).isEmpty();
        assertThat(judged).isPositive();
    }
}
