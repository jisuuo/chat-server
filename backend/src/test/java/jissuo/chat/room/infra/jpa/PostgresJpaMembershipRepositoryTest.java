package jissuo.chat.room.infra.jpa;

import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.infra.MembershipRepositoryContract;
import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"chat.repository=jpa", "logging.level.org.hibernate.SQL=DEBUG"})
@ActiveProfiles("postgres")
class PostgresJpaMembershipRepositoryTest extends MembershipRepositoryContract {

    @MockitoBean MessageRepository messages;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }
}
