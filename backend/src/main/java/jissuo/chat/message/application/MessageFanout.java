package jissuo.chat.message.application;

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

    public MessageFanout(MembershipRepository memberships, MessagePusher pusher) {
        this.memberships = memberships;
        this.pusher = pusher;
    }

    // 계획 7 결정 D4: 커밋 뒤 보내는 스레드에서 동기로 보낸다. 한 명이 느리면 모두가 늦어지는 문제(F4)를
    // 재현하려고 비동기로 미리 바꾸지 않는다. 예외도 잡지 않는다(F44)
    @TransactionalEventListener
    @DeliveryStage(value = "fanout", total = true)
    public void on(MessageSentEvent event) {
        Message message = event.message();
        pusher.push(memberships.findUserIds(message.roomId()), message,
                new DeliveryOrigin(event.transport(), event.startedNanos()));
    }
}
