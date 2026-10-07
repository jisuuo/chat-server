package jissuo.chat.experiment.lastleave;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** F19 전용 단순 삭제 방식. 실제 서비스는 빈 방을 유지한다 (ADR-012). */
final class LeaveAndDeleteRoom {
    record Result(boolean roomDeleted, String error) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    LeaveAndDeleteRoom(JdbcClient jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }
    Result leave(long roomId, long userId, Runnable afterCount) {
        try {
            return tx.execute(status -> {
                jdbc.sql("DELETE FROM room_members WHERE room_id = :r AND user_id = :u")
                        .param("r", roomId).param("u", userId).update();
                long left = jdbc.sql("SELECT COUNT(*) FROM room_members WHERE room_id = :r")
                        .param("r", roomId).query(Long.class).single();
                afterCount.run();
                if (left > 0) return new Result(false, null);
                jdbc.sql("DELETE FROM rooms WHERE id = :r").param("r", roomId).update();
                return new Result(true, null);
            });
        } catch (RuntimeException e) {
            return new Result(false, e.getClass().getSimpleName());
        }
    }
}
