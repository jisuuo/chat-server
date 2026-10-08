package jissuo.chat.experiment.ws;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/** F4: 핸드셰이크만 하고 이후 프레임을 읽지 않는다. 수신 버퍼를 작게 잡아 서버 쓰기가 빨리 막히게 한다. */
final class StalledWsClient implements AutoCloseable {

    private final Socket socket = new Socket();

    StalledWsClient(int port, long userId) throws IOException {
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("localhost", port), 5000);
        byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        String request = "GET /ws?userId=" + userId + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        // 101 응답 헤더까지만 읽고 그 뒤로는 읽지 않는다
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("핸드셰이크 중 연결이 닫혔다: " + head);
            }
            head.append((char) b);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            throw new IOException("핸드셰이크 실패: " + head);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
