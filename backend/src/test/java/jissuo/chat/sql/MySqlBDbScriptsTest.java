package jissuo.chat.sql;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=B")
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MySqlBDbScriptsTest extends DbScriptsContract {

    private static final SqlCli CLI = new MySqlCli(MySqlContainerSupport.container());

    @Override SqlCli cli() { return CLI; }
    @Override String dbDir() { return "mysql"; }
    @Override String schema() { return "b"; }

    // MySQL은 FK가 가리키는 테이블을 TRUNCATE할 수 없어 같은 세션에서 FK 검사를 잠시 끈다.
    @Override String resetSql() {
        return "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE messages; TRUNCATE TABLE messages_b; "
                + "TRUNCATE TABLE room_members; TRUNCATE TABLE rooms; TRUNCATE TABLE users; SET FOREIGN_KEY_CHECKS=1;";
    }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
