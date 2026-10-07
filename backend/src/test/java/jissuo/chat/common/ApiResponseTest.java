package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void ok는_성공이고_데이터만_담는다() {
        var response = ApiResponse.ok("안녕");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("안녕");
        assertThat(response.getError()).isNull();
    }

    @Test
    void fail은_실패이고_에러_코드와_기본_문구만_담는다() {
        var response = ApiResponse.fail(ErrorCode.ALREADY_MEMBER);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getError().code()).isEqualTo("ALREADY_MEMBER");
        assertThat(response.getError().message()).isEqualTo("이미 이 채팅방의 멤버입니다.");
    }
}
