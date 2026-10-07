package jissuo.chat.audit;

import java.time.Instant;
import jissuo.chat.auth.AuthenticationFailedEvent;
import jissuo.chat.common.RequestLogContextFilter;
import jissuo.chat.room.domain.AccessDeniedEvent;
import jissuo.chat.room.domain.MemberJoinedEvent;
import jissuo.chat.room.domain.MemberLeftEvent;
import jissuo.chat.room.domain.RoomCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** ADR-023: 감사 기록은 일반 로그와 보관 기간 및 접근 권한을 나누기 위해 전용 로거에 쓴다. */
@Component
class AuditListener {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    static final int CREDENTIAL_MAX = 64;

    // ADR-023: 롤백된 성공을 기록하지 않도록 커밋 후에만 받는다.
    @TransactionalEventListener
    void on(RoomCreatedEvent e) {
        success("ROOM_CREATED", e.userId(), e.roomId(), e.at());
    }

    @TransactionalEventListener
    void on(MemberJoinedEvent e) {
        success("MEMBER_JOINED", e.userId(), e.roomId(), e.at());
    }

    @TransactionalEventListener
    void on(MemberLeftEvent e) {
        success("MEMBER_LEFT", e.userId(), e.roomId(), e.at());
    }

    // 거부는 롤백과 무관하게 일어난 일이며 트랜잭션 없는 조회에서도 기록해야 한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    void on(AccessDeniedEvent e) {
        withUserId(e.userId(), () -> AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", "ACCESS_DENIED")
                .addKeyValue("outcome", "FAILURE")
                .addKeyValue("roomId", e.roomId())
                .addKeyValue("code", e.code())
                .addKeyValue("occurredAt", e.at().toString())
                .log());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    void on(AuthenticationFailedEvent e) {
        AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", "AUTHENTICATION_FAILED")
                .addKeyValue("outcome", "FAILURE")
                .addKeyValue("path", e.path())
                .addKeyValue("credential", truncate(e.credential()))
                .addKeyValue("occurredAt", e.at().toString())
                .log();
    }

    private static void success(String action, long userId, long roomId, Instant at) {
        withUserId(userId, () -> AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", action)
                .addKeyValue("outcome", "SUCCESS")
                .addKeyValue("roomId", roomId)
                .addKeyValue("occurredAt", at.toString())
                .log());
    }

    private static void withUserId(long userId, Runnable log) {
        // ECS는 같은 필드가 MDC와 key-value에 모두 있으면 직렬화를 거부한다.
        String previous = MDC.get(RequestLogContextFilter.USER_ID);
        MDC.put(RequestLogContextFilter.USER_ID, String.valueOf(userId));
        try {
            log.run();
        } finally {
            if (previous == null) {
                MDC.remove(RequestLogContextFilter.USER_ID);
            } else {
                MDC.put(RequestLogContextFilter.USER_ID, previous);
            }
        }
    }

    private static String truncate(String credential) {
        if (credential == null || credential.codePointCount(0, credential.length()) <= CREDENTIAL_MAX) {
            return credential;
        }
        return credential.substring(0, credential.offsetByCodePoints(0, CREDENTIAL_MAX));
    }
}
