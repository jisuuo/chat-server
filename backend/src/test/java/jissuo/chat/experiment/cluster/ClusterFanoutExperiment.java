package jissuo.chat.experiment.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F7: nginx 라운드로빈 아래에서 저장 서버에 연결된 멤버만 즉시 수신하는지 확인한다. */
@Tag("experiment")
class ClusterFanoutExperiment {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @BeforeAll
    static void clusterIsRunning() {
        ClusterControl.assumeRunning();
    }

    @Test
    void nginx_자연_분배에서_서버별_push가_갈라진다() throws Exception {
        ClusterHttp http = new ClusterHttp(ClusterControl.NGINX, JSON);
        String run = "f7-" + System.nanoTime();
        long sender = http.createUser(run + "-sender");
        long room = http.createRoom(sender, run);
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long member = http.createUser(run + "-" + i);
            http.join(member, room);
            members.add(member);
        }

        List<WsTestClient> tabs = new ArrayList<>();
        try {
            for (long member : members) {
                tabs.add(WsTestClient.connect(URI.create("ws://localhost:" + ClusterControl.NGINX
                        + "/ws?userId=" + member), ClusterControl.browserHeaders()));
            }
            Map<Long, String> storedAt = new HashMap<>();
            Map<String, Integer> sendsByServer = new HashMap<>();
            for (int i = 0; i < 20; i++) {
                HttpResponse<String> response = http.send(sender, room, "f7-message-" + i);
                assertEquals(201, response.statusCode());
                long id = JSON.readTree(response.body()).at("/data/id").asLong();
                String upstream = ClusterHttp.upstream(response);
                assertTrue(!upstream.isBlank(), "nginx X-Upstream이 비어 있다");
                storedAt.put(id, upstream);
                sendsByServer.merge(upstream, 1, Integer::sum);
                ExperimentResults.record("cluster-f7-round-robin-sends", "index,id,upstream",
                        i + "," + id + "," + upstream);
            }
            Thread.sleep(5_000);
            int noPush = 0;
            int wrongServer = 0;
            int unexpectedCount = 0;
            Set<String> receiverServers = new HashSet<>();
            for (int i = 0; i < tabs.size(); i++) {
                Set<Long> received = new HashSet<>();
                for (String frame; (frame = tabs.get(i).poll(Duration.ZERO)) != null; ) {
                    received.add(JSON.readTree(frame).at("/message/id").asLong());
                }
                Set<String> servers = new HashSet<>();
                for (long id : received) servers.add(storedAt.get(id));
                String inferredServer = servers.size() == 1 ? servers.iterator().next() : "unknown";
                if (servers.size() == 1) receiverServers.add(inferredServer);
                int expectedAtServer = sendsByServer.getOrDefault(inferredServer, 0);
                int missing = 20 - received.size();
                if (received.isEmpty()) noPush++;
                if (servers.size() > 1) wrongServer++;
                if (received.size() != expectedAtServer) unexpectedCount++;
                ExperimentResults.record("cluster-f7-round-robin",
                        "memberIndex,memberId,inferredServer,receivedCount,expectedAtServer,missingCount,receivedIds",
                        String.join(",", Integer.toString(i), Long.toString(members.get(i)), inferredServer,
                                Integer.toString(received.size()), Integer.toString(expectedAtServer),
                                Integer.toString(missing), csv(received)));
            }
            JsonNode stored = JSON.readTree(http.latest(members.getFirst(), room).body()).at("/data/messages");
            ExperimentResults.record("cluster-f7-round-robin-summary",
                    "roomId,sendsByServer,storedLatestCount,noPush,wrongServer",
                    room + "," + csv(sendsByServer) + "," + stored.size() + "," + noPush + "," + wrongServer);
            assertEquals(20, stored.size(), "REST 조회는 두 서버가 저장한 메시지를 모두 본다");
            assertEquals(2, sendsByServer.size(), "REST 요청이 양쪽 앱에 분배되어야 한다");
            assertEquals(2, receiverServers.size(), "WebSocket 연결도 양쪽 앱에 분배되어야 한다");
            assertEquals(0, wrongServer, "다른 저장 서버에서 생성한 push가 도착했다");
            assertEquals(0, noPush, "수신자의 연결 서버를 추정할 수 있어야 한다");
            assertEquals(0, unexpectedCount, "연결된 서버가 저장한 메시지를 모두 받아야 한다");
        } finally {
            for (WsTestClient tab : tabs) tab.close();
        }
    }

    private static String csv(Object value) {
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }
}
