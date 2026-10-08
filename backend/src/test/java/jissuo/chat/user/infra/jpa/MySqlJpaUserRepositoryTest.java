package jissuo.chat.user.infra.jpa;

import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.domain.RoomRepository;
import jissuo.chat.user.infra.UserRepositoryContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "chat.repository=jpa")
@ActiveProfiles("mysql")
class MySqlJpaUserRepositoryTest extends UserRepositoryContract {

    @MockitoBean MessageRepository messages;
    @MockitoBean RoomRepository rooms;
    @MockitoBean MembershipRepository memberships;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
