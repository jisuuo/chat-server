package jissuo.chat.message.infra.jpa;

import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.message.infra.MessageRepositoryContract;
import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"chat.repository=jpa", "chat.message-schema=A"})
@ActiveProfiles("mysql")
class MySqlJpaMessageRepositoryTest extends MessageRepositoryContract {

    @Autowired SpringDataMessageRepository messages;

    @Override protected String tableName() { return "messages"; }
    @Override protected MessageRepository timeRepository() { return new JpaMessageRepository(messages, true); }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
