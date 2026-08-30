import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported from {@code com.fluid.enhanced.consumer.AdvancedKafkaConsumerTest}.
 *
 * <p>The original only asserted that handlers could be constructed — it never
 * ran the poll loop, because doing so needed a broker. These drive the real
 * loop through the {@code newConsumer} seam with a {@link MockConsumer}, so
 * the delivery, ordering and commit behaviour is actually exercised.
 */
class AdvancedKafkaConsumerTest {

    private static final TopicPartition P0 = new TopicPartition("t", 0);
    private static final TopicPartition P1 = new TopicPartition("t", 1);

    private MockConsumer<String, String> mock;
    private AdvancedKafkaConsumer consumer;
    private Properties lastConfig;

    @BeforeEach
    void setUp() {
        mock = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        mock.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
        consumer = new AdvancedKafkaConsumer() {
            @Override
            protected Consumer<String, String> newConsumer(Properties config) {
                lastConfig = config;
                return mock;
            }
        };
    }

    private void assign() throws InterruptedException {
        Thread.sleep(200);
        mock.rebalance(List.of(P0, P1));
        Thread.sleep(200);
    }

    @Test
    @DisplayName("a subscription builds a consumer for its group and broker")
    void consumerCreation() {
        consumer.subscribe("host:9092", "grp", List.of("t"), r -> { });

        assertEquals("host:9092", lastConfig.get("bootstrap.servers"));
        assertEquals("grp", lastConfig.get("group.id"));
        assertEquals(1, consumer.getMetrics().get("activeConsumers"));
    }

    @Test
    @DisplayName("records reach the handler and offsets are committed")
    void deliversAndCommits() throws Exception {
        CountDownLatch seen = new CountDownLatch(2);
        List<String> got = Collections.synchronizedList(new ArrayList<>());

        consumer.subscribe("h:9092", "g", List.of("t"), r -> { got.add(r.value()); seen.countDown(); });
        assign();
        mock.addRecord(new ConsumerRecord<>("t", 0, 0, "k", "one"));
        mock.addRecord(new ConsumerRecord<>("t", 0, 1, "k", "two"));

        assertTrue(seen.await(5, TimeUnit.SECONDS), "handler was not invoked");
        Thread.sleep(500);

        assertEquals(List.of("one", "two"), got);
        assertEquals(2L, committed(P0));
    }

    @Test
    @DisplayName("per-partition ordering holds and a failure stops only its own partition")
    void failureIsolatesPartition() throws Exception {
        CountDownLatch seen = new CountDownLatch(4);
        List<String> got = Collections.synchronizedList(new ArrayList<>());

        consumer.subscribe("h:9092", "g", List.of("t"), r -> {
            got.add(r.value());
            seen.countDown();
            if ("POISON".equals(r.value())) {
                throw new IllegalStateException("boom");
            }
        });
        assign();
        mock.addRecord(new ConsumerRecord<>("t", 0, 0, "k", "p0-a"));
        mock.addRecord(new ConsumerRecord<>("t", 0, 1, "k", "p0-b"));
        mock.addRecord(new ConsumerRecord<>("t", 1, 0, "k", "p1-a"));
        mock.addRecord(new ConsumerRecord<>("t", 1, 1, "k", "POISON"));
        mock.addRecord(new ConsumerRecord<>("t", 1, 2, "k", "p1-after"));

        assertTrue(seen.await(5, TimeUnit.SECONDS), "handlers were not invoked");
        Thread.sleep(600);

        // Healthy partition fully processed, in order, fully committed.
        assertEquals(List.of("p0-a", "p0-b"), got.stream().filter(v -> v.startsWith("p0")).toList());
        assertEquals(2L, committed(P0));

        // Failing partition stops at the poison record rather than skipping it.
        assertEquals(1L, committed(P1));
        assertTrue(!got.contains("p1-after"),
            "records after a failure must not be delivered ahead of it");
    }

    @Test
    @DisplayName("batch consumption hands over whole batches")
    void batchHandler() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        List<Integer> sizes = Collections.synchronizedList(new ArrayList<>());

        consumer.consumeBatch("h:9092", "g", "t", records -> {
            sizes.add(records.size());
            seen.countDown();
        });
        assign();
        mock.addRecord(new ConsumerRecord<>("t", 0, 0, "k", "a"));
        mock.addRecord(new ConsumerRecord<>("t", 0, 1, "k", "b"));

        assertTrue(seen.await(5, TimeUnit.SECONDS), "batch handler was not invoked");
        assertTrue(sizes.get(0) >= 1, "batch should carry at least one record");
    }

    @Test
    @DisplayName("listener tuning overrides reach the client")
    void overridesAreApplied() {
        Properties overrides = new Properties();
        overrides.put("max.poll.records", "42");
        overrides.put("session.timeout.ms", "17000");

        consumer.subscribe("h:9092", "g", List.of("t"), r -> { }, overrides);

        assertEquals("42", lastConfig.get("max.poll.records"));
        assertEquals("17000", lastConfig.get("session.timeout.ms"));
    }

    @Test
    @DisplayName("overrides cannot redirect the consumer away from its broker or group")
    void overridesCannotHijackIdentity() {
        Properties overrides = new Properties();
        overrides.put("bootstrap.servers", "evil:6666");
        overrides.put("group.id", "evil-group");

        consumer.subscribe("h:9092", "g", List.of("t"), r -> { }, overrides);

        assertEquals("h:9092", lastConfig.get("bootstrap.servers"));
        assertEquals("g", lastConfig.get("group.id"));
    }

    @Test
    @DisplayName("metrics report consumption")
    void metrics() throws Exception {
        CountDownLatch seen = new CountDownLatch(1);
        consumer.subscribe("h:9092", "g", List.of("t"), r -> seen.countDown());
        assign();
        mock.addRecord(new ConsumerRecord<>("t", 0, 0, "k", "hello"));

        assertTrue(seen.await(5, TimeUnit.SECONDS));
        Thread.sleep(400);

        Map<String, Object> m = consumer.getMetrics();
        assertEquals(1L, m.get("totalMessagesConsumed"));
        assertEquals(5L, m.get("totalBytesConsumed"));
    }

    @Test
    @DisplayName("shutdown wakes the poll loop and clears registrations")
    void shutdown() throws Exception {
        consumer.subscribe("h:9092", "g", List.of("t"), r -> { });
        assign();

        consumer.shutdown();

        assertEquals(0, consumer.getMetrics().get("activeConsumers"));
        assertTrue(mock.closed(), "poll loop should close its consumer on the way out");
    }

    @Test
    @DisplayName("the handler interfaces are usable as intended")
    void functionalInterfaces() {
        AdvancedKafkaConsumer.MessageHandler single = r -> { };
        AdvancedKafkaConsumer.BatchMessageHandler batch = rs -> { };
        // ManualOffsetHandler declares two methods, so it is not a lambda type.
        AdvancedKafkaConsumer.ManualOffsetHandler manual =
            new AdvancedKafkaConsumer.ManualOffsetHandler() {
                @Override public void handleMessage(ConsumerRecord<String, String> r) { }
                @Override public void handleError(ConsumerRecord<String, String> r, Exception e) { }
            };

        assertNotNull(single);
        assertNotNull(batch);
        assertNotNull(manual);
    }

    private long committed(TopicPartition tp) {
        OffsetAndMetadata o = mock.committed(Set.of(tp)).get(tp);
        return o == null ? -1L : o.offset();
    }
}
