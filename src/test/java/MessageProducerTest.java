import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ported from the original packaged producer suite.
 *
 * <p>The original constructed a real {@code MessageProducer} and sent to
 * a live address, so every test blocked on metadata until {@code max.block.ms}
 * expired. These drive a {@link MockProducer} through the {@code newProducer}
 * seam instead: no broker, deterministic, and able to assert on what was
 * actually sent rather than only that a future was returned.
 */
class MessageProducerTest {

    private MockProducer<String, String> mock;
    private MessageProducer producer;

    /** Captures the config each producer was built with. */
    private Properties lastConfig;

    @BeforeEach
    void setUp() {
        mock = new MockProducer<>(true, new StringSerializer(), new StringSerializer());
        producer = new MessageProducer() {
            @Override
            protected Producer<String, String> newProducer(Properties config) {
                lastConfig = config;
                return mock;
            }
        };
    }

    @Test
    @DisplayName("a message reaches the producer with its topic and value")
    void sendsMessage() throws Exception {
        CompletableFuture<RecordMetadata> future = producer.sendMessage("test-topic", "test-message");

        assertNotNull(future);
        assertNotNull(future.get(5, TimeUnit.SECONDS));

        List<ProducerRecord<String, String>> sent = mock.history();
        assertEquals(1, sent.size());
        assertEquals("test-topic", sent.get(0).topic());
        assertEquals("test-message", sent.get(0).value());
        assertNull(sent.get(0).key());
    }

    @Test
    @DisplayName("a key is carried through")
    void sendsMessageWithKey() throws Exception {
        producer.sendMessage("test-topic", "key1", "test-message").get(5, TimeUnit.SECONDS);

        assertEquals("key1", mock.history().get(0).key());
    }

    @Test
    @DisplayName("an explicit partition is carried through")
    void sendsMessageWithPartition() throws Exception {
        producer.sendMessage("test-topic", "key1", "test-message", 0).get(5, TimeUnit.SECONDS);

        assertEquals(0, mock.history().get(0).partition());
    }

    @Test
    @DisplayName("headers are attached to the record")
    void sendsMessageWithHeaders() throws Exception {
        producer.sendMessageWithHeaders("test-topic", "key1", "test-message",
            Map.of("header1", "value1")).get(5, TimeUnit.SECONDS);

        ProducerRecord<String, String> record = mock.history().get(0);
        assertNotNull(record.headers().lastHeader("header1"));
        assertEquals("value1",
            new String(record.headers().lastHeader("header1").value()));
    }

    @Test
    @DisplayName("a batch sends every message and completes once")
    void sendsBatch() throws Exception {
        producer.sendBatch("test-topic", List.of("a", "b", "c")).get(5, TimeUnit.SECONDS);

        assertEquals(3, mock.history().size());
        assertEquals(List.of("a", "b", "c"),
            mock.history().stream().map(ProducerRecord::value).toList());
    }

    @Test
    @DisplayName("an empty batch completes without sending")
    void emptyBatchIsANoOp() throws Exception {
        producer.sendBatch("test-topic", List.of()).get(5, TimeUnit.SECONDS);
        producer.sendBatch("test-topic", null).get(5, TimeUnit.SECONDS);

        assertEquals(0, mock.history().size());
    }

    @Test
    @DisplayName("a send failure surfaces as KafkaProductionException")
    void sendFailureIsWrapped() {
        MockProducer<String, String> failing =
            new MockProducer<>(false, new StringSerializer(), new StringSerializer());
        MessageProducer p = new MessageProducer() {
            @Override
            protected Producer<String, String> newProducer(Properties config) {
                return failing;
            }
        };

        CompletableFuture<RecordMetadata> future = p.sendMessage("t", "m");
        failing.errorNext(new RuntimeException("broker exploded"));

        ExecutionException thrown =
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(KafkaProductionException.class, thrown.getCause());
    }

    @Test
    @DisplayName("metrics count what was actually sent")
    void tracksMetrics() throws Exception {
        producer.sendMessage("t", "hello").get(5, TimeUnit.SECONDS);
        producer.sendMessage("t", "world").get(5, TimeUnit.SECONDS);

        Map<String, Object> metrics = producer.getMetrics();
        assertEquals(2L, metrics.get("totalMessagesSent"));
        assertEquals(10L, metrics.get("totalBytesSent"));
        assertEquals(1, metrics.get("activeProducers"));
    }

    @Test
    @DisplayName("each broker address gets its own producer, configured for it")
    void producerPerBroker() {
        producer.sendMessage("alpha:1111", "t", null, "m", null);
        assertEquals("alpha:1111", lastConfig.get("bootstrap.servers"));

        producer.sendMessage("beta:2222", "t", null, "m", null);
        assertEquals("beta:2222", lastConfig.get("bootstrap.servers"));

        assertEquals(2, producer.getMetrics().get("activeProducers"));
    }

    @Test
    @DisplayName("shutdown flushes and closes")
    void shutdownClosesProducers() throws Exception {
        producer.sendMessage("t", "m").get(5, TimeUnit.SECONDS);
        producer.shutdown();

        assertTrue(mock.closed());
        assertEquals(0, producer.getMetrics().get("activeProducers"));
    }
}
