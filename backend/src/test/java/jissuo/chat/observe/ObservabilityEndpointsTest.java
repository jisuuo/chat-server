package jissuo.chat.observe;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("mysql")
class ObservabilityEndpointsTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 헬스_체크는_인증_없이_DB_상태를_보여준다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void 프로메테우스는_p99_계산용_버킷과_커넥션_풀_지표를_DB_태그와_함께_낸다() throws Exception {
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("application=\"chat\"")))
                .andExpect(content().string(containsString("db=\"mysql\"")))
                .andExpect(content().string(containsString("schema=\"A\"")));
    }

    @Test
    void 기본_프로필에서는_loggers를_열지_않는다() throws Exception {
        mvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
    }
}
