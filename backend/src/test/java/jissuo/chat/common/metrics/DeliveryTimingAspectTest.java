package jissuo.chat.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageSentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class DeliveryTimingAspectTest {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    static class Stages {
        @DeliveryStage(value = "receive", transport = "rest")
        public void receive() {}

        @DeliveryStage("save")
        public void save(DeliveryOrigin origin) {}

        @DeliveryStage(value = "fanout", total = true)
        public void fanout(MessageSentEvent event) {}

        @DeliveryStage(value = "fanout", total = true)
        public void failFanout(MessageSentEvent event) {
            throw new IllegalStateException("fanout 실패");
        }

        @DeliveryStage("push")
        public void fail(DeliveryOrigin origin) {
            throw new IllegalStateException("push 실패");
        }
    }

    Stages proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Stages());
        factory.setProxyTargetClass(true);
        factory.addAspect(new DeliveryTimingAspect(meters));
        return factory.getProxy();
    }

    @Test
    void 단계별_통로와_전체_시간을_기록한다() {
        Stages stages = proxy();
        stages.receive();
        stages.save(DeliveryOrigin.start("ws"));
        assertThat(meters.get("chat.delivery.stage").tags("stage", "receive", "transport", "rest").timer().count()).isEqualTo(1);
        assertThat(meters.get("chat.delivery.stage").tags("stage", "save", "transport", "ws").timer().count()).isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();

        long started = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        MessageSentEvent event = new MessageSentEvent(
                new Message(1, 1, 1, new MessageContent("a"), Instant.now()), started, "rest");
        stages.fanout(event);
        assertThat(meters.get("chat.delivery.total").tags("transport", "rest").timer().totalTime(TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void 예외가_나도_단계_시간을_기록한다() {
        assertThatThrownBy(() -> proxy().fail(DeliveryOrigin.start("rest"))).hasMessage("push 실패");
        assertThat(meters.get("chat.delivery.stage").tags("stage", "push", "transport", "rest").timer().count()).isEqualTo(1);
    }

    @Test
    void fanout_실패는_단계_시간만_기록하고_완료_시간은_기록하지_않는다() {
        MessageSentEvent event = new MessageSentEvent(
                new Message(1, 1, 1, new MessageContent("a"), Instant.now()), System.nanoTime(), "rest");

        assertThatThrownBy(() -> proxy().failFanout(event)).hasMessage("fanout 실패");
        assertThat(meters.get("chat.delivery.stage").tags("stage", "fanout", "transport", "rest").timer().count())
                .isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
    }
}
