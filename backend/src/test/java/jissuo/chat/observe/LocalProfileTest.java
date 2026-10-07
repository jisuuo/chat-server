package jissuo.chat.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mysql"})
class LocalProfileTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 우리_코드는_TRACE_외부는_INFO이고_SQL_커넥션풀_트랜잭션_로그만_연다() {
        assertThat(level("jissuo.chat")).isEqualTo(Level.TRACE);
        assertThat(level(Logger.ROOT_LOGGER_NAME)).isEqualTo(Level.INFO);
        assertThat(level("org.springframework.jdbc.core")).isEqualTo(Level.TRACE);
        assertThat(level("com.zaxxer.hikari")).isEqualTo(Level.DEBUG);
        assertThat(level("org.springframework.jdbc.support.JdbcTransactionManager")).isEqualTo(Level.DEBUG);
    }

    @Test
    void loggers와_헬스_상세를_연다() throws Exception {
        mvc.perform(get("/actuator/loggers/jissuo.chat")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db.details.database").exists());
    }

    static Level level(String name) {
        return ((Logger) LoggerFactory.getLogger(name)).getLevel();
    }
}
