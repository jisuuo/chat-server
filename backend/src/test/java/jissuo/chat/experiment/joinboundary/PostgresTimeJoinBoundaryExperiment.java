package jissuo.chat.experiment.joinboundary;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64", "chat.join-boundary=time"})
@ActiveProfiles("postgres")
@DirtiesContext
class PostgresTimeJoinBoundaryExperiment extends JoinBoundaryExperiment {
    @Override String condition() { return "pg-rc-time"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { PostgresContainerSupport.register(registry); }
}
