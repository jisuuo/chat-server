package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 계획 7: WebSocket 수신·전송 계약. 두 DB에서 같은 결과를 보장한다 (JDBC 기본 저장소). */
abstract class ChatWebSocketContract {

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    final List<WsTestClient> clients = new ArrayList<>();
    ChatHttp http;
    long sender;
    long member;
    long stranger;
    long room;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        http = new ChatHttp(port, json);
        sender = addUser("보내는 사람");
        member = addUser("받는 사람");
        stranger = addUser("다른 사람");
        room = http.createRoom(sender, "실시간");
        http.join(member, room);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (WsTestClient client : clients) {
            client.close();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == 0);
    }

    @Test
    void REST로_보내면_같은_방_멤버의_모든_탭과_보낸_사람이_본문_전체를_받는다() throws Exception {
        WsTestClient memberTab1 = connect(member);
        WsTestClient memberTab2 = connect(member);
        WsTestClient senderTab = connect(sender);

        HttpResponse<String> response = http.send(sender, room, "안녕\n😀");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode sent = json.readTree(response.body()).get("data");
        for (WsTestClient tab : List.of(memberTab1, memberTab2, senderTab)) {
            JsonNode frame = json.readTree(tab.next());
            assertThat(frame.get("type").asString()).isEqualTo("message");
            // 계획 7 결정 D3: REST 응답(MessageResponse)과 같은 모양
            assertThat(frame.get("message")).isEqualTo(sent);
        }
    }

    @Test
    void 비멤버와_다른_방_멤버는_받지_않는다() throws Exception {
        long otherRoom = http.createRoom(stranger, "다른 방");
        WsTestClient strangerTab = connect(stranger);
        WsTestClient memberTab = connect(member);

        http.send(sender, room, "우리 방");
        http.send(stranger, otherRoom, "다른 방 메시지");

        assertThat(json.readTree(memberTab.next()).at("/message/content").asString()).isEqualTo("우리 방");
        assertThat(json.readTree(strangerTab.next()).at("/message/content").asString()).isEqualTo("다른 방 메시지");
        assertThat(memberTab.poll(Duration.ofMillis(500))).isNull();
        assertThat(strangerTab.poll(Duration.ofMillis(500))).isNull();
    }

    @Test
    void 거절된_전송은_push하지_않는다() throws Exception {
        WsTestClient memberTab = connect(member);

        assertThat(http.send(stranger, room, "몰래").statusCode()).isEqualTo(403);

        assertThat(memberTab.poll(Duration.ofMillis(500))).isNull();
    }

    WsTestClient connect(long userId) throws Exception {
        int before = (int) sessions();
        WsTestClient client = WsTestClient.connect(port, userId);
        clients.add(client);
        // 클라이언트의 연결 완료와 서버의 저장소 등록은 순서가 보장되지 않는다
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before + 1);
        return client;
    }

    double sessions() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    long addUser(String nickname) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:nickname, :at)")
                .param("nickname", nickname)
                .param("at", LocalDateTime.now(ZoneOffset.UTC))
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
