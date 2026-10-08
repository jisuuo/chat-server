package jissuo.chat.experiment.cluster;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

final class ClusterHttp {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final int port;
    private final JsonMapper json;

    ClusterHttp(int port, JsonMapper json) {
        this.port = port;
        this.json = json;
    }

    long createUser(String nickname) throws Exception {
        HttpResponse<String> response = post("/api/dev/users", null,
                json.writeValueAsString(Map.of("nickname", nickname)));
        return id(response);
    }

    long createRoom(long userId, String name) throws Exception {
        return id(post("/api/rooms", userId, json.writeValueAsString(Map.of("name", name))));
    }

    void join(long userId, long roomId) throws Exception {
        post("/api/rooms/" + roomId + "/members", userId, null);
    }

    HttpResponse<String> send(long userId, long roomId, String content) throws Exception {
        return post("/api/rooms/" + roomId + "/messages", userId,
                json.writeValueAsString(Map.of("content", content)));
    }

    HttpResponse<String> latest(long userId, long roomId) throws Exception {
        HttpRequest request = base("/api/rooms/" + roomId + "/messages", userId).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String upstream(HttpResponse<?> response) {
        return response.headers().firstValue("X-Upstream").orElse("");
    }

    private long id(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).at("/data/id").asLong();
    }

    private HttpResponse<String> post(String path, Long userId, String body) throws Exception {
        HttpRequest.Builder request = base(path, userId);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder base(String path, Long userId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (userId != null) {
            request.header("X-User-Id", Long.toString(userId));
        }
        return request;
    }
}
