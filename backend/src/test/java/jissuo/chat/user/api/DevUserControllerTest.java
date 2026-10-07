package jissuo.chat.user.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.auth.HeaderUserIdAuthenticator;
import jissuo.chat.user.application.UserService;
import jissuo.chat.user.domain.Nickname;
import jissuo.chat.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(DevUserController.class)
@ActiveProfiles("local")
@Import({UserService.class, HeaderUserIdAuthenticator.class, DevUserControllerTest.Fakes.class})
class DevUserControllerTest {

    static final Instant NOW = Instant.parse("2026-10-07T01:02:03Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingUserRepository users;

    @BeforeEach
    void clear() {
        users.saved.clear();
    }

    @Test
    void 헤더_없이_만들면_201과_id_닉네임을_돌려준다() throws Exception {
        create("{\"nickname\": \"철수\"}")
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"success": true, "data": {"id": 7, "nickname": "철수"}, "error": null}
                        """, JsonCompareMode.STRICT));

        assertThat(users.saved).containsExactly(new Saved(new Nickname("철수"), NOW));
    }

    @Test
    void 이모지_50개_닉네임은_받는다() throws Exception {
        create("{\"nickname\": \"" + "😀".repeat(50) + "\"}")
                .andExpect(status().isCreated());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"nickname\": null}", "{\"nickname\": \"\"}", "{\"nickname\": \" \"}"})
    void 닉네임이_없거나_공백뿐이면_400(String body) throws Exception {
        create(body)
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"success": false, "data": null,
                         "error": {"code": "INVALID_REQUEST", "message": "요청 값이 올바르지 않습니다."}}
                        """, JsonCompareMode.STRICT));

        assertThat(users.saved).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\ud800", "a\\u0000b", "a\\tb", "a\\nb"})
    void 짝_없는_서로게이트나_제어_문자가_있으면_400(String escaped) throws Exception {
        // ADR-050, F25: JSON 문법은 짝 없는 서로게이트와 NUL도 이스케이프로 허용하므로 Jackson을 통과해 Java 문자열이 된다
        create("{\"nickname\": \"" + escaped + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(users.saved).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    void 닉네임이_51자면_400(String unit) throws Exception {
        create("{\"nickname\": \"" + unit.repeat(51) + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(users.saved).isEmpty();
    }

    private ResultActions create(String body) throws Exception {
        return mvc.perform(post("/api/dev/users").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    record Saved(Nickname nickname, Instant createdAt) {
    }

    static class RecordingUserRepository implements UserRepository {

        final List<Saved> saved = new ArrayList<>();

        @Override
        public long save(Nickname nickname, Instant createdAt) {
            saved.add(new Saved(nickname, createdAt));
            return 7;
        }
    }

    @TestConfiguration
    static class Fakes {

        @Bean
        RecordingUserRepository userRepository() {
            return new RecordingUserRepository();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
