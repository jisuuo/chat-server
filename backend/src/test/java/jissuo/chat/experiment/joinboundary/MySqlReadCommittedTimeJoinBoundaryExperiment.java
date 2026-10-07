package jissuo.chat.experiment.joinboundary;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64", "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED", "chat.join-boundary=time"})
@ActiveProfiles("mysql")
@DirtiesContext
class MySqlReadCommittedTimeJoinBoundaryExperiment extends JoinBoundaryExperiment {
    @Override String condition() { return "mysql-rc-time"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
}
