package jissuo.chat.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(GlobalExceptionHandlerTest.TestController.class)
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void 성공_응답은_data를_담고_error는_null이다() throws Exception {
        mvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success": true, "data": "안녕", "error": null}
                        """, JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void ChatException은_에러_코드의_상태와_문구로_바뀐다(ErrorCode code) throws Exception {
        mvc.perform(get("/test/chat-exception/" + code.name()))
                .andExpect(status().is(code.status().value()))
                .andExpect(content().json("""
                        {"success": false, "data": null,
                         "error": {"code": "%s", "message": "%s"}}
                        """.formatted(code.name(), code.message()), JsonCompareMode.STRICT));
    }

    @Test
    void 본문_검증_실패는_400() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": " "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 본문_파싱_실패는_400() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 쿼리_파라미터_타입_오류는_400() throws Exception {
        mvc.perform(get("/test/param").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 쿼리_파라미터_범위_오류는_400() throws Exception {
        mvc.perform(get("/test/param").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 필수_쿼리_파라미터_누락은_400() throws Exception {
        mvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 지원하지_않는_Content_Type은_400() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("name"))
                .andExpect(status().isBadRequest())
                .andExpect(errorCode("INVALID_REQUEST"));
    }

    @Test
    void 없는_주소는_404() throws Exception {
        mvc.perform(get("/test/없는-주소"))
                .andExpect(status().isNotFound())
                .andExpect(errorCode("NOT_FOUND"));
    }

    @Test
    void 허용하지_않은_메서드는_405() throws Exception {
        mvc.perform(post("/test/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(errorCode("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 중복_키_위반은_409_ALREADY_MEMBER() throws Exception {
        mvc.perform(get("/test/duplicate-key"))
                .andExpect(status().isConflict())
                .andExpect(errorCode("ALREADY_MEMBER"));
    }

    @Test
    void 그_밖의_무결성_위반은_500이고_상세를_숨긴다() throws Exception {
        mvc.perform(get("/test/integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"success": false, "data": null,
                         "error": {"code": "INTERNAL_ERROR", "message": "서버 오류가 발생했습니다."}}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void 예상하지_못한_예외는_500이고_상세를_숨긴다() throws Exception {
        mvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("""
                        {"success": false, "data": null,
                         "error": {"code": "INTERNAL_ERROR", "message": "서버 오류가 발생했습니다."}}
                        """, JsonCompareMode.STRICT));
    }

    private static org.springframework.test.web.servlet.ResultMatcher errorCode(String code) {
        return content().json("""
                {"success": false, "data": null, "error": {"code": "%s"}}
                """.formatted(code), JsonCompareMode.LENIENT);
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("안녕");
        }

        @GetMapping("/test/chat-exception/{code}")
        void chatException(@PathVariable ErrorCode code) {
            throw new ChatException(code);
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {
        }

        @GetMapping("/test/param")
        void param(@RequestParam @Max(100) int size) {
        }

        @GetMapping("/test/duplicate-key")
        void duplicateKey() {
            throw new DuplicateKeyException("Duplicate entry '1-2' for key 'PRIMARY'");
        }

        @GetMapping("/test/integrity")
        void integrity() {
            throw new DataIntegrityViolationException("FK 위반: room_members.user_id");
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("내부 상세");
        }
    }
}
