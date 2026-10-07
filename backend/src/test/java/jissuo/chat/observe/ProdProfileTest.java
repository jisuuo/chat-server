package jissuo.chat.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
@ActiveProfiles({"prod", "mysql"})
class ProdProfileTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 우리_코드는_INFO_외부는_WARN이다() {
        assertThat(LocalProfileTest.level("jissuo.chat")).isEqualTo(Level.INFO);
        assertThat(LocalProfileTest.level(Logger.ROOT_LOGGER_NAME)).isEqualTo(Level.WARN);
        assertThat(LocalProfileTest.level("ACCESS")).isEqualTo(Level.INFO);
    }

    @Test
    void loggers를_공개하지_않는다() throws Exception {
        mvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
    }
}
