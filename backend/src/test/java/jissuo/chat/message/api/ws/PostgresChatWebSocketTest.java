package jissuo.chat.message.api.ws;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("postgres")
class PostgresChatWebSocketTest extends ChatWebSocketContract {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }
}
