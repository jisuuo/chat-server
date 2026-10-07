package jissuo.chat.experiment.leavesend;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64"})
@ActiveProfiles("mysql")
@DirtiesContext
class MySqlLeaveSendExperiment extends LeaveSendExperiment {
    @Override String condition() { return "mysql-rr"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
}
