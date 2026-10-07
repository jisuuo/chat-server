package jissuo.chat.experiment.lastleave;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64"})
@ActiveProfiles("postgres")
@DirtiesContext
class PostgresLastLeaveExperiment extends LastLeaveExperiment {
    @Override String condition() { return "pg-rc"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { PostgresContainerSupport.register(registry); }
}
