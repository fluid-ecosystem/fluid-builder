import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Maven test path reaches the flat sources.
 *
 * <p>Deliberately narrow: it covers the pure, environment-independent parts
 * of {@link KafkaConfig}. Porting the wider suite is separate work.
 */
class KafkaConfigTest {

    @Test
    @DisplayName("blank broker address inherits the framework default")
    void blankAddressInheritsDefault() {
        String expected = KafkaConfig.defaultBootstrapServers();

        assertEquals(expected, KafkaConfig.resolveBootstrapServers(null));
        assertEquals(expected, KafkaConfig.resolveBootstrapServers(""));
        assertEquals(expected, KafkaConfig.resolveBootstrapServers("   "));
    }

    @Test
    @DisplayName("an explicit broker address is honoured verbatim")
    void explicitAddressWins() {
        assertEquals("broker.example:9999",
            KafkaConfig.resolveBootstrapServers("broker.example:9999"));
    }

    @Test
    @DisplayName("codecs needing an absent library are rejected with a usable message")
    void unavailableCodecsAreRejected() {
        for (String codec : new String[]{"snappy", "lz4", "zstd"}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KafkaConfig.validateCompressionType(codec));
            assertTrue(e.getMessage().contains(codec),
                "message should name the codec: " + e.getMessage());
            assertTrue(e.getMessage().contains(KafkaConfig.COMPRESSION_TYPE_ENV),
                "message should name the override: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("codecs needing no extra library are accepted")
    void availableCodecsAreAccepted() {
        KafkaConfig.validateCompressionType("none");
        KafkaConfig.validateCompressionType("gzip");
    }

    @Test
    @DisplayName("consumer config disables auto-commit so offsets follow processing")
    void consumerConfigDisablesAutoCommit() {
        Properties config = KafkaConfig.createConsumerConfig("a-group");

        assertEquals("false", config.get("enable.auto.commit"));
        assertEquals("a-group", config.get("group.id"));
        assertNotNull(config.get("bootstrap.servers"));
    }

    @Test
    @DisplayName("producer config only ever selects a usable codec")
    void producerConfigUsesUsableCodec() {
        Properties config = KafkaConfig.createProducerConfig();

        // createProducerConfig validates before returning, so reaching here at
        // all means the codec is loadable.
        assertEquals(KafkaConfig.defaultCompressionType(), config.get("compression.type"));
        assertEquals("all", config.get("acks"));
    }
}
