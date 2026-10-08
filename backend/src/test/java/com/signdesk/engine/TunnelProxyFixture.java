package com.signdesk.engine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Local-only proxy fixture: every tunnel goes to one explicitly supplied loopback service. */
final class TunnelProxyFixture implements AutoCloseable {
    final AtomicInteger hits = new AtomicInteger();
    final AtomicReference<String> target = new AtomicReference<>();
    private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "test-proxy");
        t.setDaemon(true);
        return t;
    });

    TunnelProxyFixture(int targetPort, boolean socks) throws IOException {
        workers.submit(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    sockets.add(client);
                    workers.submit(() -> connect(client, targetPort, socks));
                } catch (IOException e) {
                    if (!server.isClosed()) throw new UncheckedIOException(e);
                }
            }
        });
    }

    int port() { return server.getLocalPort(); }

    private void connect(Socket client, int targetPort, boolean socks) {
        try (client; Socket destination = new Socket("127.0.0.1", targetPort)) {
            sockets.add(destination);
            client.setSoTimeout(5000);
            destination.setSoTimeout(5000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            if (socks) {
                if (input.read() != 5) throw new IOException("SOCKS version");
                int methods = input.read();
                if (methods < 1 || input.readNBytes(methods).length != methods) throw new EOFException();
                output.write(new byte[] {5, 0}); output.flush();
                if (input.read() != 5 || input.read() != 1 || input.read() != 0) throw new IOException("SOCKS CONNECT");
                int type = input.read();
                String host = switch (type) {
                    case 1 -> InetAddress.getByAddress(input.readNBytes(4)).getHostAddress();
                    case 4 -> InetAddress.getByAddress(input.readNBytes(16)).getHostAddress();
                    case 3 -> new String(input.readNBytes(input.read()), StandardCharsets.US_ASCII);
                    default -> throw new IOException("SOCKS address");
                };
                int port = new DataInputStream(input).readUnsignedShort();
                if (port != targetPort) throw new IOException("Unexpected fixture destination");
                target.set(host + ":" + port);
                output.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            } else {
                ByteArrayOutputStream headers = new ByteArrayOutputStream();
                int value;
                while ((value = input.read()) != -1 && headers.size() < 8192) {
                    headers.write(value);
                    if (headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) break;
                }
                String line = headers.toString(StandardCharsets.US_ASCII).split("\r\n", 2)[0];
                if (!line.equals("CONNECT localhost:" + targetPort + " HTTP/1.1"))
                    throw new IOException("Unexpected CONNECT destination");
                target.set(line);
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            hits.incrementAndGet();
            output.flush();
            var upload = workers.submit(() -> copy(input, destination));
            copy(destination.getInputStream(), client);
            upload.cancel(true);
        } catch (IOException ignored) {
            // TLS rejection, disconnect and deadlines deliberately close fixture tunnels.
        }
    }

    private void copy(InputStream input, Socket output) {
        try { input.transferTo(output.getOutputStream()); }
        catch (IOException ignored) { }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) socket.close();
        workers.shutdownNow();
    }
}
