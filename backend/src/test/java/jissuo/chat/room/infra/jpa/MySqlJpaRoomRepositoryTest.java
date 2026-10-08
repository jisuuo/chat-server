package jissuo.chat.room.infra.jpa;

import jissuo.chat.message.domain.MessageRepository;
import jissuo.chat.room.domain.MembershipRepository;
import jissuo.chat.room.infra.RoomRepositoryContract;
import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "chat.repository=jpa")
@ActiveProfiles("mysql")
class MySqlJpaRoomRepositoryTest extends RoomRepositoryContract {

    @MockitoBean MessageRepository messages;
    @MockitoBean MembershipRepository memberships;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
