import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class RelayTests {
    private static final Relay.Policy POLICY = new Relay.Policy(3, 100, 200);
    private static final Relay.Event EVENT = new Relay.Event("evt_1", "{\"payment\":42}");
    private int passed;

    public static void main(String[] args) throws Exception {
        var tests = new RelayTests();
        tests.run("capped exponential retry and not-before time", tests::retry);
        tests.run("nonretryable and success HTTP outcomes", tests::terminalOutcomes);
        tests.run("pending restart, dead queue, explicit replay", tests::restartReplay);
        tests.run("same ID re-enqueue and conflicting payload", tests::duplicateEnqueue);
        tests.run("stable processing order", tests::stableOrder);
        tests.run("transport timeout and interruption", tests::transportFailures);
        tests.run("invalid snapshot stays untouched", tests::badSnapshot);
        tests.run("signed HTTP, invalid auth, and persisted dedup", tests::httpDedup);
        tests.run("concurrent HTTP duplicates", tests::parallelDedup);
        System.out.println(tests.passed + " tests passed");
    }

    private void retry(Path path) throws Exception {
        var time = new AtomicLong(0);
        var replies = new ArrayDeque<>(List.of(429, 500, 204));
        var relay = new Relay(path, POLICY, time::get, e -> replies.remove());
        check(POLICY.delay(999) == 200, "policy cap");
        relay.enqueue(EVENT);
        check(relay.runDue().get(0).dueMs() == 100, "first delay");
        time.set(99);
        check(relay.runDue().isEmpty(), "alarm ran early");
        time.set(100);
        check(relay.runDue().get(0).dueMs() == 300, "second delay");
        time.set(300);
        check(relay.runDue().get(0).status() == Relay.Status.DELIVERED, "2xx not delivered");
        check(replies.isEmpty() && relay.get(EVENT.id()).attempts() == 3, "attempt count");
        check(relay.runDue().isEmpty(), "delivered event resent");
        var timeout = new Relay(path, POLICY, time::get, e -> 408);
        timeout.enqueue(new Relay.Event("http_timeout", "{}"));
        check(timeout.runDue().get(0).status() == Relay.Status.PENDING, "HTTP 408 was not retried");
    }

    private void terminalOutcomes(Path path) throws Exception {
        var relay = new Relay(path, POLICY, () -> 0, e -> Integer.parseInt(e.id()));
        for (int status : List.of(200, 204, 302, 400, 401, 404, 409, 422)) {
            relay.enqueue(new Relay.Event(Integer.toString(status), "{}"));
        }
        for (var result : relay.runDue()) {
            int code = Integer.parseInt(result.event().id());
            check(result.attempts() == 1, "wrong attempt");
            check(result.status() == (code < 300 ? Relay.Status.DELIVERED : Relay.Status.DEAD), "HTTP " + code);
        }
        check(relay.runDue().isEmpty(), "terminal event retried");
    }

    private void restartReplay(Path path) throws Exception {
        var time = new AtomicLong(0);
        var replies = new ArrayDeque<>(List.of(503, 503, 503, 204));
        Relay.Sender sender = event -> {
            check(event.equals(EVENT), "replay changed original event");
            return replies.remove();
        };
        var relay = new Relay(path, POLICY, time::get, sender);
        relay.enqueue(EVENT);
        check(!relay.replay(EVENT.id()), "pending event replayed");
        relay.runDue();
        relay = new Relay(path, POLICY, time::get, sender);
        check(relay.get(EVENT.id()).attempts() == 1 && relay.runDue().isEmpty(), "pending retry not persisted");
        time.set(100);
        relay.runDue();
        time.set(300);
        check(relay.runDue().get(0).status() == Relay.Status.DEAD, "not dead after third attempt");
        relay = new Relay(path, POLICY, time::get, sender);
        check(relay.get(EVENT.id()).attempts() == 3, "dead queue not persisted");
        check(!relay.replay("missing") && relay.replay(EVENT.id()), "manual replay decision");
        relay = new Relay(path, POLICY, time::get, sender);
        check(relay.runDue().get(0).attempts() == 1, "replay did not reset budget");
        check(relay.get(EVENT.id()).status() == Relay.Status.DELIVERED && replies.isEmpty(), "replay not delivered");
    }

    private void duplicateEnqueue(Path path) throws Exception {
        var relay = new Relay(path, POLICY, () -> 0, e -> 204);
        check(relay.enqueue(EVENT) && !relay.enqueue(EVENT), "same event queued twice");
        expect(IllegalArgumentException.class, () -> relay.enqueue(new Relay.Event(EVENT.id(), "{\"different\":true}")));
        expect(IllegalArgumentException.class, () -> new Relay.Event("../outside", "{}"));
        expect(IllegalArgumentException.class, () -> new Relay.Event("x", "£".repeat(4097)));
        check(relay.runDue().size() == 1, "dedup count");
    }

    private void stableOrder(Path path) throws Exception {
        var time = new AtomicLong(0);
        var relay = new Relay(path, POLICY, time::get, e -> 204);
        relay.enqueue(new Relay.Event("first_time", "{}"));
        time.set(1);
        relay.enqueue(new Relay.Event("z", "{}"));
        relay.enqueue(new Relay.Event("a", "{}"));
        var order = relay.runDue().stream().map(d -> d.event().id()).toList();
        check(order.equals(List.of("first_time", "a", "z")), order.toString());
    }

    private void transportFailures(Path path) throws Exception {
        var time = new AtomicLong(0);
        var attempts = new AtomicInteger(0);
        var relay = new Relay(path, POLICY, time::get, e -> {
            if (attempts.getAndIncrement() == 0) throw new HttpTimeoutException("test");
            if (attempts.get() == 2) throw new InterruptedException("test");
            return 204;
        });
        relay.enqueue(EVENT);
        check(relay.runDue().get(0).result().equals("timed out"), "not classified as timeout");
        time.set(100);
        expect(InterruptedException.class, relay::runDue);
        check(relay.get(EVENT.id()).attempts() == 1, "interrupted attempt persisted");
        check(relay.runDue().get(0).status() == Relay.Status.DELIVERED, "retry was lost");
    }

    private void badSnapshot(Path path) throws Exception {
        var bytes = new byte[] {0x57, 0x48, 0x52, 0x31, 0, 0, 0, 1};
        Files.write(path, bytes);
        expect(IOException.class, () -> new Relay(path, POLICY, () -> 0, e -> 204));
        check(Arrays.equals(Files.readAllBytes(path), bytes), "bad state overwritten");
    }

    private void httpDedup(Path path) throws Exception {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 11);
        var inbox = path.resolveSibling("inbox");
        var client = LoopbackWebhooks.client();
        try (var receiver = receiver(inbox, key)) {
            var correct = LoopbackWebhooks.sender(receiver.port(), key, Duration.ofSeconds(3), client);
            var wrong = LoopbackWebhooks.sender(receiver.port(), new byte[32], Duration.ofSeconds(3), client);
            check(wrong.send(EVENT) == 401 && !receiver.committed(EVENT.id()), "bad HMAC caused a receipt");
            check(correct.send(EVENT) == 204 && receiver.committed(EVENT.id()), "signed request failed");
        }
        try (var receiver = receiver(inbox, key)) {
            var correct = LoopbackWebhooks.sender(receiver.port(), key, Duration.ofSeconds(3), client);
            check(correct.send(EVENT) == 204, "identical retry after restart did not succeed");
            check(correct.send(new Relay.Event(EVENT.id(), "{\"payment\":43}")) == 409, "conflicting payload accepted");
            check(Files.readString(inbox.resolve(EVENT.id() + ".bin")).equals(EVENT.body()), "receipt changed");
        }
    }

    private void parallelDedup(Path path) throws Exception {
        var inbox = path.resolveSibling("inbox");
        byte[] key = new byte[32];
        var client = LoopbackWebhooks.client();
        try (var receiver = receiver(inbox, key)) {
            var sender = LoopbackWebhooks.sender(receiver.port(), key, Duration.ofSeconds(3), client);
            var calls = new ArrayList<CompletableFuture<Integer>>();
            for (int i = 0; i < 6; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try { return sender.send(EVENT); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
            for (var call : calls) check(call.get() == 204, "parallel response failed");
            try (var files = Files.list(inbox)) {
                check(files.count() == 1, "duplicate receipt on disk");
            }
            var missing = HttpRequest.newBuilder(LoopbackWebhooks.uri(receiver.port()))
                .header("X-Event-Id", "evt_without_signature").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
            check(client.send(missing, HttpResponse.BodyHandlers.discarding()).statusCode() == 401,
                "unsigned request not rejected");
        }
    }

    private static LoopbackWebhooks.Receiver receiver(Path inbox, byte[] key) throws IOException {
        return new LoopbackWebhooks.Receiver(inbox, key, id -> LoopbackWebhooks.Mode.NORMAL, Duration.ZERO);
    }

    private void run(String name, Test test) throws Exception {
        var temp = Files.createTempDirectory("relay-test-");
        try {
            test.run(temp.resolve("state.bin"));
            passed++;
            System.out.println("PASS " + name);
        } finally {
            try (var files = Files.walk(temp)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Exception> expected, Action action) throws Exception {
        try { action.run(); }
        catch (Exception e) {
            if (expected.isInstance(e)) return;
            throw e;
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    @FunctionalInterface private interface Test { void run(Path root) throws Exception; }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
