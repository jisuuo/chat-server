package jissuo.chat.room.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RoomNameTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void 길이가_1에서_50자면_만들어진다(int length) {
        String value = "가".repeat(length);

        assertThat(new RoomName(value).value()).isEqualTo(value);
    }

    @Test
    void 이모지_50개는_50자로_센다() {
        // ADR-048: 두 DB의 VARCHAR(50)과 같은 기준으로 센다
        String value = "😀".repeat(50);

        assertThat(new RoomName(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void 비어_있거나_공백뿐이면_만들_수_없다(String value) {
        assertThatThrownBy(() -> new RoomName(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\ud800", "a\u0000b", "a\tb", "a\nb"})
    void 짝_없는_서로게이트나_제어_문자가_있으면_만들_수_없다(String value) {
        // ADR-050: 닉네임과 같은 규칙
        assertThatThrownBy(() -> new RoomName(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null이면_만들_수_없다() {
        assertThatThrownBy(() -> new RoomName(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    void 길이가_51자면_만들_수_없다(String unit) {
        assertThatThrownBy(() -> new RoomName(unit.repeat(51))).isInstanceOf(IllegalArgumentException.class);
    }
}
