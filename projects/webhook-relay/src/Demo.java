import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Run: java -cp out Demo */
public final class Demo {
    private Demo() {}

    public static void main(String[] args) throws Exception {
        boolean pause = args.length == 1 && args[0].equals("--pause-before-replay");
        if (args.length != 0 && !pause) {
            System.err.println("Usage: java -cp out Demo [--pause-before-replay]");
            System.exit(2);
        }
        var work = Files.createTempDirectory("webhook-relay-demo-");
        try {
            run(work, pause);
        } finally {
            try (var files = Files.walk(work)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void run(Path work, boolean pause) throws IOException, InterruptedException {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        var now = new AtomicLong(0);
        var outage = new AtomicBoolean(true);
        var statePath = work.resolve("queue.bin");
        var inbox = work.resolve("inbox");
        var policy = new Relay.Policy(3, 100, 400);

        try (var receiver = new LoopbackWebhooks.Receiver(inbox, key, id -> switch (id) {
            case "evt_timeout" -> LoopbackWebhooks.Mode.DELAY_AFTER_COMMIT;
            case "evt_outage" -> outage.get() ? LoopbackWebhooks.Mode.UNAVAILABLE : LoopbackWebhooks.Mode.NORMAL;
            default -> LoopbackWebhooks.Mode.NORMAL;
        }, Duration.ofSeconds(3))) {
            var client = LoopbackWebhooks.client();
            // Warm the connection with a normal event before the timeout scenario.
            var sender = LoopbackWebhooks.sender(receiver.port(), key, Duration.ofMillis(900), client);
            var relay = new Relay(statePath, policy, now::get, sender);
            relay.enqueue(new Relay.Event("evt_ok", "{\"type\":\"payment.succeeded\",\"amount_minor\":12500}"));
            runOnce(relay, now);
            relay.enqueue(new Relay.Event("evt_timeout", "{\"type\":\"payment.succeeded\",\"amount_minor\":2200}"));
            runOnce(relay, now);
            if (!receiver.committed("evt_timeout") || !"timed out".equals(relay.get("evt_timeout").result()))
                throw new IllegalStateException("demo expected sender timeout after the receiver committed");
            System.out.println("Receiver had already committed evt_timeout before the sender retried.");
            // Reload the sender state before its retry to simulate a single-worker restart.
            relay = new Relay(statePath, policy, now::get, sender);
            System.out.println("Sender restarted; retry survived on disk.");
            now.set(100);
            runOnce(relay, now);

            relay.enqueue(new Relay.Event("evt_outage", "{\"type\":\"payment.succeeded\",\"amount_minor\":500}"));
            runOnce(relay, now);
            now.set(200);
            runOnce(relay, now);
            now.set(400);
            runOnce(relay, now);
            if (relay.get("evt_outage").status() != Relay.Status.DEAD)
                throw new IllegalStateException("demo expected evt_outage in failed queue");
            if (pause) {
                System.out.println("Press Enter to recover the receiver and replay the dead event.");
                if (new BufferedReader(new InputStreamReader(System.in)).readLine() == null)
                    throw new IOException("input ended before manual replay");
            }
            outage.set(false);
            if (!relay.replay("evt_outage")) throw new IllegalStateException("replay rejected");
            System.out.println("Receiver recovered. Operator replayed evt_outage with its original ID.");
            runOnce(relay, now);
            if (relay.get("evt_timeout").status() != Relay.Status.DELIVERED ||
                relay.get("evt_outage").status() != Relay.Status.DELIVERED)
                throw new IllegalStateException("demo did not deliver the replay or timeout retry");
            try (var files = Files.list(inbox)) {
                long receipts = files.filter(path -> path.getFileName().toString().endsWith(".bin")).count();
                if (receipts != 3) throw new IllegalStateException("demo expected three receipts");
                System.out.println("Receiver stored 3 receipts for 3 distinct events. No duplicate effect.");
            }
        }
    }

    private static void runOnce(Relay relay, AtomicLong now) throws IOException, InterruptedException {
        for (var outcome : relay.runDue()) {
            System.out.printf("t=%d ms  %s  try %d  %s  %s%s%n",
                now.get(), outcome.event().id(), outcome.attempts(), outcome.result(), outcome.status(),
                outcome.status() == Relay.Status.PENDING ? "  (next at " + outcome.dueMs() + " ms)" : "");
        }
    }
}
