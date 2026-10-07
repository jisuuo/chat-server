package jissuo.chat.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mysql.MySQLContainer;

/**
 * JVM당 MySQL 컨테이너를 한 번만 띄운다.
 * 테스트 클래스에서 {@code @ActiveProfiles("mysql")}과 함께 {@code @DynamicPropertySource}로 {@link #register}를 부른다.
 */
public final class MySqlContainerSupport {

    private static final MySQLContainer CONTAINER = new MySQLContainer("mysql:8.4.11");

    static {
        CONTAINER.start();
    }

    private MySqlContainerSupport() {
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", CONTAINER::getUsername);
        registry.add("spring.datasource.password", CONTAINER::getPassword);
    }

    /** db/ SQL을 컨테이너 안의 클라이언트로 실행하기 위해 노출한다 (계획 2). */
    public static MySQLContainer container() {
        return CONTAINER;
    }
}
