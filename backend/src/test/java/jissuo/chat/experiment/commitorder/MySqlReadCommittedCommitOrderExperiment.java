package jissuo.chat.experiment.commitorder;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64", "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"})
@ActiveProfiles("mysql")
@DirtiesContext
class MySqlReadCommittedCommitOrderExperiment extends CommitOrderExperiment {
    @Override String condition() { return "mysql-rc"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
}
