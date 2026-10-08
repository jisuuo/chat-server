package jissuo.chat.message.api.ws;

import jakarta.validation.Validator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.common.RequestLogContextFilter;
import jissuo.chat.message.api.SendMessageRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-136: WS 이벤트마다 새 요청 ID를 붙인다. userId와 requestId는 ECS 중복 필드를 피하려고 MDC에만 둔다. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WsAccessLogAspect {
    private static final Logger WS_ACCESS = LoggerFactory.getLogger("WS_ACCESS");
    private final JsonMapper json;
    private final Validator validator;

    public WsAccessLogAspect(JsonMapper json, Validator validator) {
        this.json = json;
        this.validator = validator;
    }

    @Around("execution(* jissuo.chat.message.api.ws.ChatWebSocketHandler.afterConnectionEstablished(..))")
    public Object connect(ProceedingJoinPoint pjp) throws Throwable {
        return logged(pjp, (WebSocketSession) pjp.getArgs()[0], "connect", null);
    }

    @Around("execution(* jissuo.chat.message.api.ws.ChatWebSocketHandler.afterConnectionClosed(..))")
    public Object close(ProceedingJoinPoint pjp) throws Throwable {
        return logged(pjp, (WebSocketSession) pjp.getArgs()[0], "close", (CloseStatus) pjp.getArgs()[1]);
    }

    @Around("execution(* jissuo.chat.message.api.ws.ChatFrameHandler.handle(..))")
    public Object frame(ProceedingJoinPoint pjp) throws Throwable {
        return logged(pjp, (WebSocketSession) pjp.getArgs()[0], "frame", null);
    }

    private Object logged(ProceedingJoinPoint pjp, WebSocketSession session, String event, CloseStatus status)
            throws Throwable {
        MDC.put(RequestLogContextFilter.REQUEST_ID, UUID.randomUUID().toString());
        AuthUser user = ChatWebSocketHandler.userOf(session);
        if (user != null) {
            MDC.put(RequestLogContextFilter.USER_ID, String.valueOf(user.id()));
        }
        long started = System.nanoTime();
        Object result = null;
        String outcome = "exception";
        try {
            result = pjp.proceed();
            outcome = result instanceof FrameOutcome frame ? frame.result() : "ok";
            return result;
        } finally {
            try {
                LoggingEventBuilder log = WS_ACCESS.atInfo().setMessage("ws_access")
                        .addKeyValue("event", event)
                        .addKeyValue("sessionId", session.getId())
                        .addKeyValue("result", outcome)
                        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                FrameOutcome frame = result instanceof FrameOutcome found ? found
                        : "frame".equals(event) ? frameOf((String) pjp.getArgs()[1]) : null;
                if (frame != null) {
                    log = log.addKeyValue("frameType", frame.type()).addKeyValue("roomId", frame.roomId());
                }
                if (status != null) {
                    log = log.addKeyValue("closeCode", status.getCode());
                }
                log.log();
            } finally {
                MDC.remove(RequestLogContextFilter.REQUEST_ID);
                MDC.remove(RequestLogContextFilter.USER_ID);
            }
        }
    }

    private FrameOutcome frameOf(String payload) {
        // 예외 경로에서만 본문을 다시 읽는다. 원래 전송 예외를 로그 보강 때문에 가리지 않는다.
        try {
            JsonNode body = json.readTree(payload);
            JsonNode roomId = body == null ? null : body.get("roomId");
            boolean validRoom = roomId != null && roomId.isIntegralNumber() && roomId.canConvertToLong();
            if (!validRoom) {
                return new FrameOutcome("invalid", null, "exception");
            }
            SendFrame frame = json.treeToValue(body, SendFrame.class);
            if (frame == null || !"send".equals(frame.type()) || frame.roomId() == null
                    || !validator.validate(new SendMessageRequest(frame.content())).isEmpty()) {
                return new FrameOutcome("invalid", frame == null ? null : frame.roomId(), "exception");
            }
            return new FrameOutcome("send", frame.roomId(), "exception");
        } catch (RuntimeException e) {
            return new FrameOutcome("invalid", null, "exception");
        }
    }
}
