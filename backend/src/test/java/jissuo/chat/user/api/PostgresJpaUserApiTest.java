package jissuo.chat.user.api;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.repository=jpa")
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class PostgresJpaUserApiTest extends UserApiContract {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) { PostgresContainerSupport.register(registry); }
}
