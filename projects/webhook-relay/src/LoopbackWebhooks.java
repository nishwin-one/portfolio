import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** HTTP helpers restricted to an IPv4 loopback demo; no external destination is configurable. */
final class LoopbackWebhooks {
    private static final int MAX_PAYLOAD = 8_192;
    private static final String EVENT_HEADER = "X-Event-Id";
    private static final String HMAC_HEADER = "X-Event-Hmac";

    private LoopbackWebhooks() {}

    static HttpClient client() {
        // Never follow a redirect or route through a system-configured proxy.
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException e) {}
            })
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    }

    static Relay.Sender sender(int port, byte[] key, Duration timeout, HttpClient client) {
        var target = uri(port);
        var secret = key.clone();
        return event -> {
            var body = event.body().getBytes(StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header(EVENT_HEADER, event.id())
                .header(HMAC_HEADER, HexFormat.of().formatHex(mac(secret, event.id(), body)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }

    static URI uri(int port) {
        return URI.create("http://127.0.0.1:" + port + "/webhook");
    }

    private static byte[] mac(byte[] key, String id, byte[] body) {
        try {
            var hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            hmac.update(id.getBytes(StandardCharsets.US_ASCII));
            hmac.update((byte) '\n');
            return hmac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not use HMAC-SHA256", e);
        }
    }

    enum Mode {
        NORMAL, UNAVAILABLE, DELAY_AFTER_COMMIT
    }

    static final class Receiver implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService threads;
        private final Path inbox;
        private final byte[] secret;
        private final Function<String, Mode> behavior;
        private final Duration delayedResponse;

        Receiver(Path inbox, byte[] key, Function<String, Mode> behavior, Duration delayedResponse)
            throws IOException {
            this.inbox = Files.createDirectories(inbox);
            this.secret = key.clone();
            this.behavior = behavior;
            this.delayedResponse = delayedResponse;
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            this.threads = Executors.newFixedThreadPool(4);
            server.setExecutor(threads);
            server.createContext("/webhook", this::handle);
            server.start();
        }

        int port() { return server.getAddress().getPort(); }
        boolean committed(String id) {
            if (!id.matches("[a-zA-Z0-9_-]{1,64}")) return false;
            return Files.isRegularFile(inbox.resolve(id + ".bin"));
        }

        private void handle(HttpExchange x) throws IOException {
            try {
                if (!x.getRequestURI().getPath().equals("/webhook")) { respond(x, 404); return; }
                if (!x.getRequestMethod().equals("POST")) { respond(x, 405); return; }
                var id = x.getRequestHeaders().getFirst(EVENT_HEADER);
                var signature = x.getRequestHeaders().getFirst(HMAC_HEADER);
                if (id == null || !id.matches("[a-zA-Z0-9_-]{1,64}")) { respond(x, 400); return; }
                var body = x.getRequestBody().readNBytes(MAX_PAYLOAD + 1);
                if (body.length > MAX_PAYLOAD || body.length == 0) { respond(x, 400); return; }
                if (!valid(id, body, signature)) { respond(x, 401); return; }

                var mode = behavior.apply(id);
                if (mode == Mode.UNAVAILABLE) { respond(x, 503); return; }
                int outcome = commit(id, body);
                if (outcome == 201 && mode == Mode.DELAY_AFTER_COMMIT) {
                    try { Thread.sleep(delayedResponse.toMillis()); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
                respond(x, outcome == 201 ? 204 : outcome);
            } finally {
                x.close();
            }
        }

        private boolean valid(String id, byte[] body, String signature) {
            if (signature == null || !signature.matches("[0-9A-Fa-f]{64}")) return false;
            return MessageDigest.isEqual(mac(secret, id, body), HexFormat.of().parseHex(signature));
        }

        /**
         * The file is both the synthetic business effect and the dedup record. Holding the
         * lock makes a single receiver instance safe against simultaneous duplicates.
         */
        private synchronized int commit(String id, byte[] body) throws IOException {
            var target = inbox.resolve(id + ".bin");
            if (Files.exists(target)) {
                return Arrays.equals(Files.readAllBytes(target), body) ? 204 : 409;
            }
            var temp = Files.createTempFile(inbox, "receipt-", ".tmp");
            try {
                Files.write(temp, body);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return 201;
        }

        private static void respond(HttpExchange x, int status) throws IOException {
            x.sendResponseHeaders(status, -1);
        }

        @Override public void close() {
            server.stop(0);
            threads.shutdownNow();
        }
    }
}
