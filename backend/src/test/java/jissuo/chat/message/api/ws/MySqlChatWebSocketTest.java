package jissuo.chat.message.api.ws;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class MySqlChatWebSocketTest extends ChatWebSocketContract {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
