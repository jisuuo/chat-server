package jissuo.chat.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class DeliveryTimingAspectTest {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    static class Stages {
        @DeliveryStage(value = "receive", transport = "rest")
        public void receive() {}

        @DeliveryStage("save")
        public void save(DeliveryOrigin origin) {}

        @DeliveryStage("fanout")
        public void fanout(DeliveryOrigin origin) {}

        @DeliveryStage("fanout")
        public void failFanout(DeliveryOrigin origin) {
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
    void 단계별_통로를_기록한다() {
        Stages stages = proxy();
        stages.receive();
        stages.save(DeliveryOrigin.start("ws"));
        assertThat(meters.get("chat.delivery.stage").tags("stage", "receive", "transport", "rest").timer().count()).isEqualTo(1);
        assertThat(meters.get("chat.delivery.stage").tags("stage", "save", "transport", "ws").timer().count()).isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();

        stages.fanout(DeliveryOrigin.start("rest"));
        assertThat(meters.get("chat.delivery.stage").tags("stage", "fanout", "transport", "rest")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void 예외가_나도_단계_시간을_기록한다() {
        assertThatThrownBy(() -> proxy().fail(DeliveryOrigin.start("rest"))).hasMessage("push 실패");
        assertThat(meters.get("chat.delivery.stage").tags("stage", "push", "transport", "rest").timer().count()).isEqualTo(1);
    }

    @Test
    void fanout_실패는_단계_시간만_기록하고_완료_시간은_기록하지_않는다() {
        assertThatThrownBy(() -> proxy().failFanout(DeliveryOrigin.start("rest"))).hasMessage("fanout 실패");
        assertThat(meters.get("chat.delivery.stage").tags("stage", "fanout", "transport", "rest").timer().count())
                .isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
    }
}
