package jissuo.chat.sql;

import jissuo.chat.support.PostgresContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=B")
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
class PostgresBDbScriptsTest extends DbScriptsContract {

    private static final SqlCli CLI = new PostgresCli(PostgresContainerSupport.container());

    @Override SqlCli cli() { return CLI; }
    @Override String dbDir() { return "postgresql"; }
    @Override String schema() { return "b"; }

    // FK로 연결된 테이블을 모두 비우므로 CASCADE 없이도 id를 다시 시작할 수 있다.
    @Override String resetSql() {
        return "TRUNCATE messages, messages_b, room_members, rooms, users RESTART IDENTITY;";
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.register(registry);
    }
}
