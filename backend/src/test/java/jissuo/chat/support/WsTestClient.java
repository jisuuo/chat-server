package jissuo.chat.support;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;

/** 서버가 보낸 텍스트 프레임을 큐에 모으는 테스트용 클라이언트. 실험에서도 쓴다. */
public final class WsTestClient implements AutoCloseable {

    public record ReceivedFrame(String payload, long receivedNanos) {}

    // 연결마다 컨테이너를 새로 만들면 실험(수백 연결)에서 스레드가 크게 늘어 하나를 같이 쓴다
    private static final StandardWebSocketClient CLIENT = new StandardWebSocketClient();

    private final BlockingQueue<ReceivedFrame> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final WebSocketSession session;

    private WsTestClient(URI uri, WebSocketHttpHeaders headers) throws Exception {
        session = CLIENT.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                frames.add(new ReceivedFrame(message.getPayload(), System.nanoTime()));
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                closed.complete(status);
            }
        }, headers, uri).get(5, TimeUnit.SECONDS);
    }

    public static WsTestClient connect(int port, long userId) throws Exception {
        return connect(URI.create("ws://localhost:" + port + "/ws?userId=" + userId), new WebSocketHttpHeaders());
    }

    public static WsTestClient connectRaw(int port, String query) throws Exception {
        return connect(URI.create("ws://localhost:" + port + "/ws?" + query), new WebSocketHttpHeaders());
    }

    public static WsTestClient connect(URI uri, WebSocketHttpHeaders headers) throws Exception {
        return new WsTestClient(uri, headers);
    }

    public void send(String json) throws IOException {
        session.sendMessage(new TextMessage(json));
    }

    public String next() throws InterruptedException {
        ReceivedFrame frame = frames.poll(5, TimeUnit.SECONDS);
        if (frame == null) {
            throw new AssertionError("5초 안에 프레임이 오지 않았다");
        }
        return frame.payload();
    }

    public String poll(Duration wait) throws InterruptedException {
        ReceivedFrame frame = pollReceived(wait);
        return frame == null ? null : frame.payload();
    }

    public ReceivedFrame pollReceived(Duration wait) throws InterruptedException {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public CloseStatus awaitClosed() throws Exception {
        return closed.get(5, TimeUnit.SECONDS);
    }

    public boolean closedEventSeen() {
        return closed.isDone();
    }

    @Override
    public void close() throws IOException {
        session.close();
    }
}
