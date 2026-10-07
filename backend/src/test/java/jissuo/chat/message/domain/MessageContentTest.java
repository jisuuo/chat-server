package jissuo.chat.message.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageContentTest {

    @Test
    void 한_글자와_천_글자는_허용한다() {
        assertThat(new MessageContent("가").value()).isEqualTo("가");
        assertThat(new MessageContent("가".repeat(1000)).value()).hasSize(1000);
    }

    @Test
    void 이모지_천_개를_천_글자로_센다() {
        assertThat(new MessageContent("😀".repeat(1000)).value()).isEqualTo("😀".repeat(1000));
    }

    @Test
    void 비어_있거나_천_글자를_넘으면_거절한다() {
        assertThatThrownBy(() -> new MessageContent(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageContent("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageContent("😀".repeat(1001))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PostgreSQL이_저장할_수_없는_NUL_문자를_거절한다() {
        assertThatThrownBy(() -> new MessageContent("앞\0뒤"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
