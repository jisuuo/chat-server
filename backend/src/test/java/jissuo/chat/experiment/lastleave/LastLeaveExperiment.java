package jissuo.chat.experiment.lastleave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.room.application.RoomService;
import jissuo.chat.room.domain.RoomRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("experiment")
abstract class LastLeaveExperiment {
    @Autowired RoomService rooms;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean RoomRepository roomRepository;
    abstract String condition();
    private LeaveAndDeleteRoom deleter() { return new LeaveAndDeleteRoom(jdbc, new TransactionTemplate(txManager)); }
    private long count(String sql, long room) { return jdbc.sql(sql).param("r", room).query(Long.class).single(); }
    private long orphans() { return jdbc.sql("SELECT COUNT(*) FROM room_members m LEFT JOIN rooms r ON r.id=m.room_id WHERE r.id IS NULL")
            .query(Long.class).single(); }

    @Test void joinCheckedBeforeRoomDeleted() throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        long a = fixture.user("f19a"), b = fixture.user("f19b");
        long room = rooms.create(a, "f19a").id();
        var once = new AtomicBoolean();
        var result = new AtomicReference<LeaveAndDeleteRoom.Result>();
        doAnswer(invocation -> {
            Object found = invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                result.set(CompletableFuture.supplyAsync(() -> deleter().leave(room, a, () -> {})).get());
            }
            return found;
        }).when(roomRepository).findById(anyLong());
        assertThatThrownBy(() -> rooms.join(b, room)).isInstanceOf(ChatException.class)
                .extracting(e -> ((ChatException) e).errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        ExperimentResults.record("f19-deterministic", "condition,scenario,join,delete,room_exists,members,orphans",
                condition() + ",checked_then_deleted,UNAUTHENTICATED," + result.get().roomDeleted() + ","
                        + count("SELECT COUNT(*) FROM rooms WHERE id=:r", room) + ","
                        + count("SELECT COUNT(*) FROM room_members WHERE room_id=:r", room) + "," + orphans());
        assertThat(result.get().roomDeleted()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM rooms WHERE id=:r", room)).isZero();
        assertThat(count("SELECT COUNT(*) FROM room_members WHERE room_id=:r", room)).isZero();
        assertThat(orphans()).isZero();
    }

    @Test void joinAfterEmptyCountRollsBackDelete() {
        var fixture = new ExperimentFixtures(jdbc);
        long a = fixture.user("f19c"), b = fixture.user("f19d");
        long room = rooms.create(a, "f19b").id();
        var result = deleter().leave(room, a, () -> {
            try { CompletableFuture.runAsync(() -> rooms.join(b, room)).get(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        ExperimentResults.record("f19-deterministic", "condition,scenario,join,delete,room_exists,members,orphans",
                condition() + ",count_then_join,ok," + result.error() + ","
                        + count("SELECT COUNT(*) FROM rooms WHERE id=:r", room) + ","
                        + count("SELECT COUNT(*) FROM room_members WHERE room_id=:r", room) + "," + orphans());
        assertThat(result.error()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM rooms WHERE id=:r", room)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM room_members WHERE room_id=:r", room)).isEqualTo(2);
        assertThat(orphans()).isZero();
    }

    @Test void naturalFrequency() throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        int tries = 1000;
        Map<String, Integer> counts = new HashMap<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < tries; i++) {
                long a = fixture.user("f19owner"), b = fixture.user("f19joiner");
                long room = rooms.create(a, "f19race").id();
                var gate = new CyclicBarrier(2);
                var delete = pool.submit(() -> { gate.await(); return deleter().leave(room, a, () -> {}); });
                var join = pool.submit(() -> {
                    gate.await();
                    try { rooms.join(b, room); return "ok"; }
                    catch (ChatException e) { return e.errorCode().name(); }
                    catch (Exception e) { return e.getClass().getSimpleName(); }
                });
                var d = delete.get();
                String j = join.get();
                String state = "condition=" + condition() + ",join=" + j + ",delete="
                        + (d.error() != null ? d.error() : d.roomDeleted() ? "deleted" : "kept")
                        + ",room_exists=" + count("SELECT COUNT(*) FROM rooms WHERE id=:r", room)
                        + ",members=" + count("SELECT COUNT(*) FROM room_members WHERE room_id=:r", room);
                counts.merge(state, 1, Integer::sum);
            }
        }
        long orphanCount = orphans();
        for (var entry : counts.entrySet())
            ExperimentResults.record("f19-frequency", "condition,join,delete,room_exists,members,count,orphans",
                    entry.getKey().replace("condition=", "").replace(",join=", ",").replace(",delete=", ",")
                            .replace(",room_exists=", ",").replace(",members=", ",") + "," + entry.getValue() + "," + orphanCount);
        assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(tries);
        assertThat(orphanCount).isZero();
    }
}
