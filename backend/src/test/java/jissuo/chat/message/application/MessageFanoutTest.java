package jissuo.chat.message.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageSentEvent;
import jissuo.chat.room.domain.MembershipRepository;
import org.junit.jupiter.api.Test;

class MessageFanoutTest {
    @Test
    void 커밋_후_멤버_조회가_실패하면_전달_실패를_기록한다() {
        MembershipRepository memberships = mock(MembershipRepository.class);
        MessagePusher pusher = mock(MessagePusher.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MessageFanout fanout = new MessageFanout(memberships, pusher, meters);
        Message message = new Message(1, 3, 1, new MessageContent("hello"), Instant.now());
        when(memberships.findUserIds(3)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> fanout.on(new MessageSentEvent(message, System.nanoTime(), "rest")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(meters.get("chat.delivery.failed").tag("transport", "rest").counter().count()).isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
        verifyNoInteractions(pusher);
    }
}
