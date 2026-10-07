package jissuo.chat.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * JVM당 PostgreSQL 컨테이너를 한 번만 띄운다.
 * 테스트 클래스에서 {@code @ActiveProfiles("postgres")}와 함께 {@code @DynamicPropertySource}로 {@link #register}를 부른다.
 */
public final class PostgresContainerSupport {

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:18.6");

    static {
        CONTAINER.start();
    }

    private PostgresContainerSupport() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
    }
}
