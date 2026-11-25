import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Queue and retry rules, independent of the HTTP client and wall clock. */
final class Relay {
    enum Status { PENDING, DELIVERED, DEAD }

    record Event(String id, String body) {
        Event {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(body, "body");
            if (!id.matches("[a-zA-Z0-9_-]{1,64}"))
                throw new IllegalArgumentException("event ID must be 1–64 ASCII letters, digits, underscores or hyphens");
            if (body.isEmpty() || !StandardCharsets.UTF_8.newEncoder().canEncode(body)
                || body.getBytes(StandardCharsets.UTF_8).length > 8_192)
                throw new IllegalArgumentException("event body must be 1–8192 UTF-8 bytes");
        }
    }

    record Delivery(Event event, int attempts, long dueMs, Status status, String result) {}

    record Policy(int maxAttempts, long baseMs, long capMs) {
        Policy {
            if (maxAttempts < 1 || baseMs < 1 || capMs < baseMs)
                throw new IllegalArgumentException("invalid retry policy");
        }

        long delay(int attempts) {
            long wait = baseMs;
            for (int i = 1; i < attempts && wait < capMs; i++)
                wait = wait > capMs / 2 ? capMs : wait * 2;
            return wait;
        }
    }

    @FunctionalInterface
    interface Sender {
        int send(Event event) throws IOException, InterruptedException;
    }

    private final Snapshot store;
    private final Policy policy;
    private final LongSupplier now;
    private final Sender sender;
    private Map<String, Delivery> rows;

    Relay(Path file, Policy policy, LongSupplier now, Sender sender) throws IOException {
        this.store = new Snapshot(file);
        this.policy = policy;
        this.now = now;
        this.sender = sender;
        this.rows = store.load();
    }

    Delivery get(String id) { return rows.get(id); }

    /** Returns false for an identical re-enqueue. Reject reuse of an ID for different content. */
    boolean enqueue(Event event) throws IOException {
        var existing = rows.get(event.id());
        if (existing != null) {
            if (!existing.event().equals(event)) throw new IllegalArgumentException("event ID already used");
            return false;
        }
        save(new Delivery(event, 0, now.getAsLong(), Status.PENDING, "queued"));
        return true;
    }

    boolean replay(String id) throws IOException {
        var row = rows.get(id);
        if (row == null || row.status() != Status.DEAD) return false;
        save(new Delivery(row.event(), 0, now.getAsLong(), Status.PENDING, "replayed"));
        return true;
    }

    /** Process a snapshot of currently due deliveries, once each, in a stable order. */
    List<Delivery> runDue() throws IOException, InterruptedException {
        long start = now.getAsLong();
        var due = rows.values().stream()
            .filter(d -> d.status() == Status.PENDING && d.dueMs() <= start)
            .sorted(Comparator.comparingLong(Delivery::dueMs).thenComparing(d -> d.event().id()))
            .toList();
        var outcomes = new ArrayList<Delivery>();
        for (var delivery : due) {
            int attempt = delivery.attempts() + 1;
            Status status;
            String result;
            boolean retry;
            try {
                int http = sender.send(delivery.event());
                result = "HTTP " + http;
                boolean ok = http >= 200 && http < 300;
                retry = http == 408 || http == 429 || (http >= 500 && http < 600);
                status = ok ? Status.DELIVERED : Status.DEAD;
            } catch (IOException e) {
                result = e instanceof java.net.http.HttpTimeoutException ? "timed out" : "transport failed";
                retry = true;
                status = Status.DEAD;
            }
            long dueMs = 0;
            if (retry && attempt < policy.maxAttempts()) {
                status = Status.PENDING;
                dueMs = Math.addExact(now.getAsLong(), policy.delay(attempt));
            }
            var updated = new Delivery(delivery.event(), attempt, dueMs, status, result);
            save(updated);
            outcomes.add(updated);
        }
        return List.copyOf(outcomes);
    }

    private void save(Delivery update) throws IOException {
        var next = new TreeMap<>(rows);
        next.put(update.event().id(), update);
        store.replace(next);
        rows = next;
    }

    /** A versioned snapshot stored via an atomic rename on the same filesystem. */
    private record Snapshot(Path path) {
        private static final int MAGIC = 0x57485231; // WHR1

        Map<String, Delivery> load() throws IOException {
            var data = new TreeMap<String, Delivery>();
            if (Files.notExists(path)) return data;
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (in.readInt() != MAGIC) throw new IOException("invalid relay state header");
                int size = in.readInt();
                if (size < 0 || size > 100_000) throw new IOException("invalid relay state length");
                for (int i = 0; i < size; i++) {
                    var event = new Event(in.readUTF(), in.readUTF());
                    int attempts = in.readInt();
                    long due = in.readLong();
                    var status = Status.valueOf(in.readUTF());
                    var result = in.readUTF();
                    if (attempts < 0 || (status != Status.PENDING && (attempts == 0 || due != 0))
                        || data.putIfAbsent(event.id(), new Delivery(event, attempts, due, status, result)) != null)
                        throw new IOException("invalid relay state record");
                }
                if (in.read() != -1) throw new IOException("unexpected trailing data in relay state");
                return data;
            } catch (EOFException | IllegalArgumentException e) {
                throw new IOException("invalid or truncated relay state", e);
            }
        }

        void replace(Map<String, Delivery> data) throws IOException {
            if (data.size() > 100_000) throw new IOException("too many events in relay state");
            var absolute = path.toAbsolutePath();
            var temp = Files.createTempFile(absolute.getParent(), "relay-", ".tmp");
            try {
                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(data.size());
                    for (var d : data.values()) {
                        out.writeUTF(d.event().id());
                        out.writeUTF(d.event().body());
                        out.writeInt(d.attempts());
                        out.writeLong(d.dueMs());
                        out.writeUTF(d.status().name());
                        out.writeUTF(d.result());
                    }
                }
                Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }
}
