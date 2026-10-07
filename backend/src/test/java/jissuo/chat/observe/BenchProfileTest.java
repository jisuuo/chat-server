package jissuo.chat.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"bench", "mysql"})
class BenchProfileTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 우리_코드는_INFO_외부는_WARN이고_SQL_로그는_따로_열지_않는다() {
        assertThat(LocalProfileTest.level("jissuo.chat")).isEqualTo(Level.INFO);
        assertThat(LocalProfileTest.level(Logger.ROOT_LOGGER_NAME)).isEqualTo(Level.WARN);
        assertThat(LocalProfileTest.level("org.springframework.jdbc.core")).isNull();
        assertThat(LocalProfileTest.level("ACCESS")).isEqualTo(Level.OFF);
    }

    @Test
    void loggers를_열고_헬스_상세는_숨긴다() throws Exception {
        mvc.perform(get("/actuator/loggers/jissuo.chat")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db.details").doesNotExist());
    }
}
