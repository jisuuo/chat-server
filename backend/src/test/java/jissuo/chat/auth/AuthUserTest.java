package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthUserTest {

    @Test
    void id가_1_이상이면_만들어진다() {
        assertThat(new AuthUser(1).id()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void id가_1보다_작으면_만들_수_없다(long id) {
        assertThatThrownBy(() -> new AuthUser(id)).isInstanceOf(IllegalArgumentException.class);
    }
}
