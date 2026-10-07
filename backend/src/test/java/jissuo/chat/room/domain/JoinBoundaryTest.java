package jissuo.chat.room.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class JoinBoundaryTest {

    static final Instant NOW = Instant.parse("2026-10-07T01:02:03.123456Z");

    @Test
    void 마지막_메시지_번호와_현재_시각을_경계로_삼는다() {
        assertThat(JoinBoundary.at(120L, NOW)).isEqualTo(new JoinBoundary(120, NOW));
    }

    @Test
    void 메시지가_없는_방이면_번호_경계는_0이다() {
        assertThat(JoinBoundary.at(null, NOW)).isEqualTo(new JoinBoundary(0, NOW));
    }
}
