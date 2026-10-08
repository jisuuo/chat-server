package jissuo.chat.experiment.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F5: 연결을 서버로 그대로 잇다가 freeze() 뒤에는 양쪽 모두 아무것도 전달하지 않고 소켓도 닫지 않는다.
 * 케이블이 빠진 것처럼 서버는 FIN도 RST도 받지 못한다.
 */
final class SilentDropProxy implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0);
    private final int target;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean frozen;

    SilentDropProxy(int target) throws IOException {
        this.target = target;
        pool.submit(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    void freeze() {
        frozen = true;
    }

    private Void accept() {
        try {
            while (!server.isClosed()) {
                Socket client = server.accept();
                Socket upstream = new Socket("localhost", target);
                sockets.add(client);
                sockets.add(upstream);
                pool.submit(() -> pump(client, upstream));
                pool.submit(() -> pump(upstream, client));
            }
        } catch (IOException e) {
            // close()로 서버 소켓을 닫으면 여기서 끝난다
        }
        return null;
    }

    private Void pump(Socket from, Socket to) throws Exception {
        byte[] buffer = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            while (true) {
                while (frozen) {
                    // 읽지도 쓰지도 않고 소켓을 열어 둔다
                    Thread.sleep(50);
                }
                int n = in.read(buffer);
                if (n < 0) {
                    // freeze 뒤 클라이언트가 FIN을 보내도 서버 쪽 소켓은 관찰 종료까지 열어 둔다.
                    if (!frozen) {
                        to.shutdownOutput();
                    }
                    return null;
                }
                while (frozen) {
                    Thread.sleep(50);
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        pool.shutdownNow();
    }
}
