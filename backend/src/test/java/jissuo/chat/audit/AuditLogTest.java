package jissuo.chat.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import jissuo.chat.common.RequestLogContextFilter;
import jissuo.chat.room.domain.RoomCreatedEvent;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class AuditLogTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    long owner;
    long stranger;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        owner = addUser("철수");
        stranger = addUser("영희");
        audit.start();
        auditLogger().addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        auditLogger().detachAppender(audit);
    }

    @Test
    void 방_생성은_커밋_후_요청_추적_ID와_함께_기록된다() throws Exception {
        String requestId = mvc.perform(post("/api/rooms").header("X-User-Id", owner)
                        .contentType("application/json").content("{\"name\":\"잡담방\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(RequestLogContextFilter.HEADER);

        assertThat(audit.list).hasSize(1);
        ILoggingEvent entry = audit.list.getFirst();
        assertThat(fields(entry)).containsEntry("action", "ROOM_CREATED").containsEntry("outcome", "SUCCESS")
                .containsEntry("roomId", roomId("잡담방"));
        assertThat(entry.getMDCPropertyMap()).containsEntry(RequestLogContextFilter.REQUEST_ID, requestId)
                .containsEntry(RequestLogContextFilter.USER_ID, String.valueOf(owner))
                .containsKey(RequestLogContextFilter.CLIENT_IP);
        assertThat(Files.readString(Path.of(System.getProperty("LOG_DIR"), "audit.json")))
                .contains("\"requestId\":\"" + requestId + "\"")
                .contains("\"action\":\"ROOM_CREATED\"");
    }

    @Test
    void 롤백된_성공_이벤트는_기록하지_않는다() {
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new RoomCreatedEvent(1, owner, Instant.now()));
            status.setRollbackOnly();
        });

        assertThat(audit.list).isEmpty();
    }

    @Test
    void 요청_밖에서_커밋된_성공_이벤트도_사용자_id를_기록하고_MDC를_복원한다() {
        tx.executeWithoutResult(status -> publisher.publishEvent(new RoomCreatedEvent(1, owner, Instant.now())));

        assertThat(audit.list).hasSize(1);
        assertThat(audit.list.getFirst().getMDCPropertyMap())
                .containsEntry(RequestLogContextFilter.USER_ID, String.valueOf(owner));
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 입장과_나가기가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(post("/api/rooms/{id}/members", roomId).header("X-User-Id", stranger)).andExpect(status().isCreated());
        mvc.perform(delete("/api/rooms/{id}/members/me", roomId).header("X-User-Id", stranger)).andExpect(status().isOk());

        assertThat(audit.list).extracting(e -> fields(e).get("action")).containsExactly("MEMBER_JOINED", "MEMBER_LEFT");
    }

    @Test
    void 비멤버_전송은_롤백되어도_거부가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(post("/api/rooms/{id}/messages", roomId).header("X-User-Id", stranger)
                        .contentType("application/json").content("{\"content\":\"안녕\"}"))
                .andExpect(status().isForbidden());

        assertThat(audit.list).hasSize(1);
        assertThat(fields(audit.list.getFirst())).containsEntry("action", "ACCESS_DENIED")
                .containsEntry("outcome", "FAILURE").containsEntry("code", "NOT_A_MEMBER")
                .containsEntry("roomId", roomId);
        assertThat(audit.list.getFirst().getMDCPropertyMap())
                .containsEntry(RequestLogContextFilter.USER_ID, String.valueOf(stranger));
    }

    @Test
    void 트랜잭션_없는_비멤버_조회도_거부가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(get("/api/rooms/{id}/messages", roomId).header("X-User-Id", stranger))
                .andExpect(status().isForbidden());

        assertThat(audit.list).extracting(e -> fields(e).get("action")).containsExactly("ACCESS_DENIED");
    }

    @Test
    void 인증_실패는_경로와_앞_64자까지의_헤더값이_기록된다() throws Exception {
        mvc.perform(get("/api/rooms").header("X-User-Id", "x".repeat(100))).andExpect(status().isUnauthorized());

        assertThat(audit.list).hasSize(1);
        assertThat(fields(audit.list.getFirst())).containsEntry("action", "AUTHENTICATION_FAILED")
                .containsEntry("outcome", "FAILURE").containsEntry("path", "/api/rooms")
                .containsEntry("credential", "x".repeat(64));
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger("AUDIT");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private long addUser(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:name, :at)")
                .param("name", nickname).param("at", LocalDateTime.parse("2026-10-07T01:02:03"))
                .update(key, "id");
        return key.getKey().longValue();
    }

    private long createRoom(String name) throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", owner)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        return roomId(name);
    }

    private long roomId(String name) {
        return jdbc.sql("SELECT id FROM rooms WHERE name = :name").param("name", name).query(Long.class).single();
    }
}
