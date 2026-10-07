package jissuo.chat.experiment.commitorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import jissuo.chat.experiment.support.Concurrently;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.message.application.MessagePage;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageCursor;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.application.RoomService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Tag("experiment")
abstract class CommitOrderExperiment {
    @Autowired MessageService messageService;
    @Autowired RoomService roomService;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean MessageRepository messages;
    abstract String condition();

    @Test void deterministicMiss() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        long owner = fixtures.user("f22");
        long room = roomService.create(owner, "f22").id();
        var beforeCommit = new AtomicReference<List<Long>>();
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            Message saved = (Message) invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> {
                    messageService.send(owner, room, "second");
                    beforeCommit.set(ids(messageService.read(owner, room, MessageCursor.after(0), 50)));
                }).get();
            }
            return saved;
        }).when(messages).save(anyLong(), anyLong(), any(), any());
        long first = messageService.send(owner, room, "first").id();
        long cursor = beforeCommit.get().getLast();
        List<Long> later = ids(messageService.read(owner, room, MessageCursor.after(cursor), 50));
        ExperimentResults.record("f22-deterministic", "condition,first,polled,next",
                condition() + "," + first + "," + beforeCommit.get() + "," + later);
        assertThat(beforeCommit.get()).containsExactly(first + 1);
        assertThat(later).isEmpty();
        assertThat(fixtures.messageIds(room)).containsExactly(first, first + 1);
    }

    @ParameterizedTest @ValueSource(ints = {1, 10, 50})
    void naturalFrequency(int writers) throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        long reader = fixtures.user("f22reader");
        long room = roomService.create(reader, "f22freq").id();
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            long user = fixtures.user("f22writer");
            roomService.join(user, room);
            users.add(user);
        }
        var received = new ArrayList<Long>();
        var polls = new AtomicInteger();
        var running = new AtomicBoolean(true);
        var poller = new Thread(() -> {
            long cursor = 0;
            while (running.get()) {
                List<Long> ids = ids(messageService.read(reader, room, MessageCursor.after(cursor), 100));
                polls.incrementAndGet();
                received.addAll(ids);
                if (!ids.isEmpty()) cursor = ids.getLast();
            }
        });
        poller.start();
        var sent = new AtomicInteger();
        var index = new AtomicInteger();
        List<Throwable> errors = Concurrently.run(writers, Duration.ofSeconds(5), () -> {
            long user = users.get(Math.floorMod(index.getAndIncrement(), users.size()));
            messageService.send(user, room, "m");
            sent.incrementAndGet();
        });
        running.set(false);
        poller.join();
        long cursor = received.isEmpty() ? 0 : received.getLast();
        while (true) {
            List<Long> next = ids(messageService.read(reader, room, MessageCursor.after(cursor), 100));
            polls.incrementAndGet();
            if (next.isEmpty()) break;
            received.addAll(next);
            cursor = next.getLast();
        }
        List<Long> all = fixtures.messageIds(room);
        var missed = new HashSet<>(all);
        missed.removeAll(received);
        ExperimentResults.record("f22-frequency", "condition,writers,sent,polls,missed",
                condition() + "," + writers + "," + sent.get() + "," + polls.get() + "," + missed.size());
        assertThat(errors).isEmpty();
        assertThat(all).hasSize(sent.get());
        assertThat(received).doesNotHaveDuplicates();
    }

    static List<Long> ids(MessagePage page) { return page.messages().stream().map(Message::id).toList(); }
}
