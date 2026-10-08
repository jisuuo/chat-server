package jissuo.chat.message.infra.jdbc;

import jissuo.chat.message.infra.MessageRepositoryContract;
import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=A")
@ActiveProfiles("mysql")
class MySqlAMessageRepositoryTest extends MessageRepositoryContract {

    @Override protected String tableName() { return "messages"; }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
