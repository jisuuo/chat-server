package jissuo.chat.message.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import jissuo.chat.common.metrics.DeliveryStage;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageSentEvent;
import jissuo.chat.room.domain.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MessageFanout {

    private final MembershipRepository memberships;
    private final MessagePusher pusher;
    private final MeterRegistry meters;

    public MessageFanout(MembershipRepository memberships, MessagePusher pusher, MeterRegistry meters) {
        this.memberships = memberships;
        this.pusher = pusher;
        this.meters = meters;
    }

    // ADR-132: 커밋 뒤 멤버를 조회한다. ADR-143: push는 세션별 송신 큐에서 완료한다.
    @TransactionalEventListener
    @DeliveryStage("fanout")
    public void on(MessageSentEvent event) {
        Message message = event.message();
        List<Long> userIds;
        try {
            userIds = memberships.findUserIds(message.roomId());
        } catch (RuntimeException e) {
            meters.counter("chat.delivery.failed", "transport", event.transport()).increment();
            throw e;
        }
        pusher.push(userIds, message, new DeliveryOrigin(event.transport(), event.startedNanos()));
    }
}
