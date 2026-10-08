package jissuo.chat.experiment.sqlcount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@Tag("experiment")
@Import(SqlCountExperiment.Counting.class)
public abstract class SqlCountExperiment {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    protected abstract String database();
    protected abstract String repository();

    long userId;
    long roomId;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        userId = addUser();
    }

    @Test
    void 요청별_SQL_실행_횟수() throws Exception {
        measure("room_create", post("/api/rooms").header("X-User-Id", userId)
                .contentType("application/json").content("{\"name\":\"측정 방\"}"), 201);
        roomId = jdbc.sql("SELECT id FROM rooms WHERE name = '측정 방'").query(Long.class).single();

        long anotherUser = addUser();
        measure("join", post("/api/rooms/{roomId}/members", roomId).header("X-User-Id", anotherUser), 201);
        measure("send", post("/api/rooms/{roomId}/messages", roomId).header("X-User-Id", userId)
                .contentType("application/json").content("{\"content\":\"안녕\"}"), 201);
        measure("latest", get("/api/rooms/{roomId}/messages", roomId).header("X-User-Id", userId), 200);
        measure("poll", get("/api/rooms/{roomId}/messages", roomId).header("X-User-Id", userId)
                .param("after", "0"), 200);
        measure("room_list", get("/api/rooms").header("X-User-Id", userId), 200);
    }

    private void measure(String scenario, MockHttpServletRequestBuilder request, int status) throws Exception {
        Counting.STATEMENTS.set(0);
        mvc.perform(request).andExpect(status().is(status));
        int statements = Counting.STATEMENTS.get();
        assertThat(statements).as(scenario + " SQL count").isPositive();
        System.out.printf("SQL_COUNT db=%s repository=%s scenario=%s statements=%d%n",
                database(), repository(), scenario, statements);
    }

    private long addUser() {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES ('측정', :at)")
                .param("at", LocalDateTime.parse("2026-10-08T00:00:00"))
                .update(key, "id");
        return key.getKey().longValue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Counting {
        static final AtomicInteger STATEMENTS = new AtomicInteger();

        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) return bean;
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (proxy, method, args) -> {
                                Object result = invoke(ds, method, args);
                                return result instanceof Connection connection ? countingConnection(connection) : result;
                            });
                }
            };
        }

        private static Connection countingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") || method.getName().equals("createStatement")) {
                            STATEMENTS.incrementAndGet();
                        }
                        Object result = invoke(connection, method, args);
                        return result instanceof Statement statement ? statement : result;
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
