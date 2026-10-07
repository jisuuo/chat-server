package jissuo.chat.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NicknameTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 50})
    void 길이가_1에서_50자면_만들어진다(int length) {
        String value = "가".repeat(length);

        assertThat(new Nickname(value).value()).isEqualTo(value);
    }

    @Test
    void 이모지_50개는_50자로_센다() {
        // String.length()로는 100이다. 두 DB의 VARCHAR(50)은 50으로 센다 (ADR-048, 측정)
        String value = "😀".repeat(50);

        assertThat(new Nickname(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void 비어_있거나_공백뿐이면_만들_수_없다(String value) {
        assertThatThrownBy(() -> new Nickname(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\ud800", "a\u0000b", "a\tb", "a\nb"})
    void 짝_없는_서로게이트나_제어_문자가_있으면_만들_수_없다(String value) {
        // ADR-050, F25: 서로게이트는 두 DB 모두 ?로 바뀌어 저장되고, NUL은 PostgreSQL만 500이 났다
        assertThatThrownBy(() -> new Nickname(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"x\":1}", "<b>철수</b>"})
    void JSON이나_HTML_모양의_글자는_그대로_받는다(String value) {
        // 출력할 때 이스케이프하는 것은 출력하는 쪽의 일이다
        assertThat(new Nickname(value).value()).isEqualTo(value);
    }

    @Test
    void null이면_만들_수_없다() {
        assertThatThrownBy(() -> new Nickname(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    void 길이가_51자면_만들_수_없다(String unit) {
        assertThatThrownBy(() -> new Nickname(unit.repeat(51))).isInstanceOf(IllegalArgumentException.class);
    }
}
