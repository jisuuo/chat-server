package jissuo.chat.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.MessageSentEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 계획 7 결정 D4·세부 7A: 트랜잭션 프록시 바깥에서 재므로 receive, save, fanout, push 시간은 서로 겹친다. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DeliveryTimingAspect {
    private final MeterRegistry meters;

    public DeliveryTimingAspect(MeterRegistry meters) {
        this.meters = meters;
    }

    @Around(value = "@annotation(stage)", argNames = "pjp,stage")
    public Object time(ProceedingJoinPoint pjp, DeliveryStage stage) throws Throwable {
        DeliveryOrigin origin = originOf(pjp.getArgs());
        String transport = !stage.transport().isEmpty() ? stage.transport()
                : origin != null ? origin.transport() : "unknown";
        long started = System.nanoTime();
        boolean completed = false;
        Object result = null;
        try {
            result = pjp.proceed();
            completed = true;
            return result;
        } finally {
            long ended = System.nanoTime();
            // 닫힌 세션은 실제 전송을 건너뛰므로 push 지연 표본에서도 제외한다.
            if (!("push".equals(stage.value()) && Boolean.FALSE.equals(result))) {
                Timer.builder("chat.delivery.stage").tag("stage", stage.value()).tag("transport", transport)
                        .register(meters).record(ended - started, TimeUnit.NANOSECONDS);
            }
            // push가 실패하면 마지막 전달이 끝나지 않았으므로 완료 지연 표본에 넣지 않는다.
            if (completed && stage.total() && origin != null) {
                Timer.builder("chat.delivery.total").tag("transport", transport)
                        .register(meters).record(ended - origin.startedNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }

    private static DeliveryOrigin originOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof DeliveryOrigin origin) {
                return origin;
            }
            if (arg instanceof MessageSentEvent event) {
                return new DeliveryOrigin(event.transport(), event.startedNanos());
            }
        }
        return null;
    }
}
