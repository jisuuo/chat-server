package jissuo.chat.schema;

import static org.assertj.core.api.Assertions.assertThat;

import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("mysql")
class MySqlSchemaMigrationTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired
    JdbcClient jdbc;

    @Test
    void 마이그레이션_후_테이블_5개가_있다() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        """)
                .query(String.class)
                .list();

        assertThat(tables).containsExactlyInAnyOrder("users", "rooms", "room_members", "messages", "messages_b");
    }
}
