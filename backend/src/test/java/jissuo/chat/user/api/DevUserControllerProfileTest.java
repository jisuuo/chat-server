package jissuo.chat.user.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jissuo.chat.auth.HeaderUserIdAuthenticator;
import jissuo.chat.common.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 개발용 API는 인증 없이 사용자를 만들 수 있으므로 local, bench가 아닌 프로필(prod)에서는 존재하지 않아야 한다.
 */
@WebMvcTest(DevUserController.class)
@Import({HeaderUserIdAuthenticator.class, ClockConfig.class})
class DevUserControllerProfileTest {

    @Autowired
    MockMvc mvc;

    @Test
    void local_bench가_아니면_404() throws Exception {
        mvc.perform(post("/api/dev/users").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\": \"철수\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("""
                        {"success": false, "data": null,
                         "error": {"code": "NOT_FOUND", "message": "요청한 주소를 찾을 수 없습니다."}}
                        """, JsonCompareMode.STRICT));
    }
}
