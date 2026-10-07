package jissuo.chat.experiment.leavesend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.experiment.support.Ticks;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.room.application.RoomService;
import jissuo.chat.room.domain.MembershipRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("experiment")
abstract class LeaveSendExperiment {
    @Autowired MessageService messages;
    @Autowired RoomService rooms;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager txManager;
    @MockitoSpyBean MembershipRepository memberships;
    abstract String condition();

    @Test void deterministicNonmemberMessage() throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        long user = fixture.user("f23");
        long room = rooms.create(user, "f23").id();
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> rooms.leave(user, room)).get();
            }
            return result;
        }).when(memberships).find(anyLong(), anyLong());
        long id = messages.send(user, room, "after leave").id();
        long memberCount = fixture.count("SELECT COUNT(*) FROM room_members WHERE room_id = :r", room);
        long messageCount = fixture.count("SELECT COUNT(*) FROM messages WHERE room_id = :r", room);
        ExperimentResults.record("f23-deterministic", "condition,message_id,members,messages",
                condition() + "," + id + "," + memberCount + "," + messageCount);
        assertThat(memberCount).isZero();
        assertThat(messageCount).isEqualTo(1);
    }

    @Test void naturalFrequency() throws Exception {
        var fixture = new ExperimentFixtures(jdbc);
        long owner = fixture.user("f23owner");
        long room = rooms.create(owner, "f23freq").id();
        int tries = 1000;
        var bothOk = new AtomicInteger();
        var send403 = new AtomicInteger();
        var violations = new AtomicInteger();
        var ambiguous = new AtomicInteger();
        var orderedBeforeLeave = new AtomicInteger();
        var other = new AtomicInteger();
        var tx = new TransactionTemplate(txManager);
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < tries; i++) {
                long user = fixture.user("f23race");
                rooms.join(user, room);
                var gate = new CyclicBarrier(2);
                var commitStart = new AtomicLong(Long.MAX_VALUE);
                var sendEnd = new AtomicLong(Long.MAX_VALUE);
                var leaveStart = new AtomicLong(Long.MAX_VALUE);
                var leaveEnd = new AtomicLong(Long.MAX_VALUE);
                var send = pool.submit(() -> {
                    gate.await();
                    try {
                        tx.executeWithoutResult(status -> {
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void beforeCommit(boolean readOnly) { commitStart.set(Ticks.next()); }
                            });
                            messages.send(user, room, "race");
                        });
                        sendEnd.set(Ticks.next());
                        return "ok";
                    } catch (ChatException e) { return e.errorCode().name(); }
                      catch (Exception e) { return e.getClass().getSimpleName(); }
                });
                var leave = pool.submit(() -> {
                    gate.await();
                    leaveStart.set(Ticks.next());
                    try { rooms.leave(user, room); leaveEnd.set(Ticks.next()); return "ok"; }
                    catch (ChatException e) { return e.errorCode().name(); }
                    catch (Exception e) { return e.getClass().getSimpleName(); }
                });
                String s = send.get(), l = leave.get();
                if (s.equals("ok") && l.equals("ok")) {
                    bothOk.incrementAndGet();
                    if (leaveEnd.get() < commitStart.get()) violations.incrementAndGet();
                    else if (sendEnd.get() < leaveStart.get()) orderedBeforeLeave.incrementAndGet();
                    else ambiguous.incrementAndGet();
                } else if (s.equals(ErrorCode.NOT_A_MEMBER.name()) && l.equals("ok")) send403.incrementAndGet();
                else other.incrementAndGet();
            }
        }
        long nonmemberMessages = jdbc.sql("""
                SELECT COUNT(*) FROM messages m LEFT JOIN room_members rm
                ON m.room_id = rm.room_id AND m.sender_id = rm.user_id
                WHERE m.room_id = :r AND rm.user_id IS NULL
                """).param("r", room).query(Long.class).single();
        ExperimentResults.record("f23-frequency",
                "condition,tries,send_ok_leave_ok,send_403,violations,ordered_before_leave,ambiguous,other_errors,nonmember_messages",
                condition() + "," + tries + "," + bothOk + "," + send403 + "," + violations + ","
                        + orderedBeforeLeave + "," + ambiguous + "," + other + "," + nonmemberMessages);
        assertThat(bothOk.get() + send403.get() + other.get()).isEqualTo(tries);
    }
}
