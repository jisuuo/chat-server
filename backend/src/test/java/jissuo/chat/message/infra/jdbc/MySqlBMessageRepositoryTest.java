package jissuo.chat.message.infra.jdbc;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=B")
@ActiveProfiles("mysql")
class MySqlBMessageRepositoryTest extends JdbcMessageRepositoryContract {

    @Override String tableName() { return "messages_b"; }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
