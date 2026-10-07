package jissuo.chat.message.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageCursorTest {

    @Test
    void 지정한_방향으로_커서를_만든다() {
        assertThat(MessageCursor.of(null, null)).isEqualTo(MessageCursor.latest());
        assertThat(MessageCursor.of(0L, null)).isEqualTo(MessageCursor.after(0));
        assertThat(MessageCursor.of(null, 7L)).isEqualTo(MessageCursor.before(7));
    }

    @Test
    void 양쪽을_함께_지정하면_거절한다() {
        assertThatThrownBy(() -> MessageCursor.of(1L, 2L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 음수_번호는_거절한다() {
        assertThatThrownBy(() -> MessageCursor.after(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MessageCursor.before(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
