package jissuo.chat.experiment.sqlcount;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.repository=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class PostgresJpaSqlCountExperiment extends SqlCountExperiment {
    @Override protected String database() { return "postgres"; }
    @Override protected String repository() { return "jpa"; }
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry registry) { PostgresContainerSupport.register(registry); }
}
