package jissuo.chat.experiment.sqlcount;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.repository=jdbc")
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MySqlJdbcSqlCountExperiment extends SqlCountExperiment {
    @Override protected String database() { return "mysql"; }
    @Override protected String repository() { return "jdbc"; }
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
}
