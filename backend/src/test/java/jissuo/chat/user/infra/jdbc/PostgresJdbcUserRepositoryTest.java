package jissuo.chat.user.infra.jdbc;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("postgres")
class PostgresJdbcUserRepositoryTest extends JdbcUserRepositoryContract {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }
}
