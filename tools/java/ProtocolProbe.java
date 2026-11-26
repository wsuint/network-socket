import ws.fabricmod.networksocket.protocol.FrameCodec;
import ws.fabricmod.networksocket.protocol.RequestIds;
import ws.fabricmod.networksocket.protocol.WireMessage;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Standalone cross-language probe: exercises the same pure-Java wire classes the mod uses
 * against the Python test server. Run it after starting tools/tcp_test_server.py:
 *
 *   java -cp build/classes/java/main;build/tmp/probe ProtocolProbe 127.0.0.1 9000 change-me probe
 */
public final class ProtocolProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: ProtocolProbe <host> <port> <token> <name>");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String token = args[2];
        String name = args[3];

        byte[] key = FrameCodec.keyFromToken(token);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(3000);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestId = RequestIds.newRequestId();
            byte[] request = FrameCodec.encrypt(
                    WireMessage.request(name, 13, requestId, RequestIds.nowSeconds()).encode(), key);
            // Split the 256-byte frame into two writes to test the server's reassembly.
            out.write(request, 0, 100);
            out.flush();
            Thread.sleep(10);
            out.write(request, 100, 156);
            out.flush();

            WireMessage response = readMessage(in, key);
            require(response.type() == WireMessage.Type.RESPONSE, "expected response");
            // The server-side business code may rewrite power, so only check the wire range.
            require(response.power() >= 0 && response.power() <= 15,
                    "response power out of range: " + response.power());
            require(response.tick() >= 0, "response tick is negative");
            require(requestId.equals(response.requestId()), "response requestid mismatch");

            String keepaliveId = RequestIds.newRequestId();
            byte[] keepalive = FrameCodec.encrypt(
                    WireMessage.keepalive(name, keepaliveId, RequestIds.nowSeconds()).encode(), key);
            out.write(keepalive);
            out.flush();

            WireMessage keepaliveOk = readMessage(in, key);
            require(keepaliveOk.type() == WireMessage.Type.KEEPALIVE_OK, "expected keepaliveok");
            require(keepaliveId.equals(keepaliveOk.requestId()), "keepaliveok requestid mismatch");

            System.out.println("PASS request+keepalive against " + host + ":" + port);
        }
    }

    private static WireMessage readMessage(InputStream in, byte[] key) throws Exception {
        byte[] frame = new byte[256];
        int offset = 0;
        while (offset < frame.length) {
            int read = in.read(frame, offset, frame.length - offset);
            if (read < 0) {
                throw new EOFException("server closed the connection");
            }
            offset += read;
        }
        return WireMessage.parse(FrameCodec.decrypt(frame, key));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            System.err.println("FAIL: " + message);
            System.exit(1);
        }
    }
}