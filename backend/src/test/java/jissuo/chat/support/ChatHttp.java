package jissuo.chat.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 실제 포트로 REST를 부르는 테스트 도우미 (WebSocket 테스트는 RANDOM_PORT라 MockMvc를 쓰지 않는다). */
public final class ChatHttp {

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;
    private final JsonMapper json;

    public ChatHttp(int port, JsonMapper json) {
        this.port = port;
        this.json = json;
    }

    public long createRoom(long userId, String name) throws Exception {
        HttpResponse<String> response = post("/api/rooms", userId, json.writeValueAsString(Map.of("name", name)));
        return json.readTree(response.body()).at("/data/id").asLong();
    }

    public void join(long userId, long roomId) throws Exception {
        post("/api/rooms/" + roomId + "/members", userId, null);
    }

    public HttpResponse<String> send(long userId, long roomId, String content) throws Exception {
        return post("/api/rooms/" + roomId + "/messages", userId, json.writeValueAsString(Map.of("content", content)));
    }

    public HttpResponse<String> get(String path, long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", Long.toString(userId)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, long userId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", Long.toString(userId));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
