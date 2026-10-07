package jissuo.chat.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoomListCursorTest {

    @Test
    void 마지막_메시지_번호가_있는_커서를_읽는다() {
        assertThat(RoomListCursor.parse("120:7")).isEqualTo(new RoomListCursor(120L, 7));
    }

    @Test
    void 메시지가_없는_방의_커서는_하이픈으로_읽는다() {
        assertThat(RoomListCursor.parse("-:3")).isEqualTo(new RoomListCursor(null, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"120:7", "-:3"})
    void 읽은_커서를_다시_쓰면_같은_문자열이다(String value) {
        assertThat(RoomListCursor.parse(value).format()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "7", "120:", ":7", "a:7", "120:b", "-:-", "1:2:3", " 1:2", "-1:2", "1:-2"})
    void 형식이_틀리면_읽을_수_없다(String value) {
        assertThatThrownBy(() -> RoomListCursor.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null이면_읽을_수_없다() {
        assertThatThrownBy(() -> RoomListCursor.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
