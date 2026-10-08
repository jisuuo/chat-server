package jissuo.chat.message.infra.jdbc;

import jissuo.chat.message.infra.MessageRepositoryContract;
import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=B")
@ActiveProfiles("postgres")
class PostgresBMessageRepositoryTest extends MessageRepositoryContract {

    @Override protected String tableName() { return "messages_b"; }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }
}
