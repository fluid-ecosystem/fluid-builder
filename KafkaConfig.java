import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * Advanced Kafka Configuration Manager
 * Provides production-ready Kafka configurations with performance optimizations,
 * security settings, and advanced features.
 */
public class KafkaConfig {
    
    /**
     * Environment variable consulted for the broker address.
     *
     * <p>Already set to {@code kafka:9092} by the example
     * {@code docker-compose.yaml} for both services.
     */
    public static final String BOOTSTRAP_SERVERS_ENV = "BOOTSTRAP_SERVERS";

    /**
     * Broker used when {@link #BOOTSTRAP_SERVERS_ENV} is unset.
     *
     * <p>Matches the Compose service name rather than {@code localhost},
     * because the framework's deployment target is a container that reaches
     * the broker by service name. A {@code localhost} default is only correct
     * for a JVM sharing a host with the broker, which is not how this runs.
     */
    public static final String FALLBACK_BOOTSTRAP_SERVERS = "kafka:9092";
    public static final int DEFAULT_PARTITIONS = 3;
    public static final short DEFAULT_REPLICATION_FACTOR = 1;
    
    /** Environment variable consulted for the producer compression codec. */
    public static final String COMPRESSION_TYPE_ENV = "KAFKA_COMPRESSION_TYPE";

    /**
     * Compression used when {@link #COMPRESSION_TYPE_ENV} is unset.
     *
     * <p>{@code gzip} is the only compressing codec that works with the
     * dependency set this framework downloads. {@code snappy}, {@code lz4}
     * and {@code zstd} all delegate to third-party libraries that
     * {@code kafka-clients} does not bundle:
     *
     * <pre>
     *   snappy -> org.xerial.snappy.SnappyOutputStream
     *   lz4    -> net.jpountz.lz4.LZ4Factory
     *   zstd   -> com.github.luben.zstd.ZstdOutputStreamNoFinalizer
     * </pre>
     *
     * <p>Any of them can be selected by adding the matching jar to the
     * dependency list; {@link #validateCompressionType(String)} checks the
     * codec is usable at startup rather than letting it fail deep in the
     * send path.
     */
    public static final String FALLBACK_COMPRESSION_TYPE = "gzip";

    // Performance Configuration
    public static final int BATCH_SIZE = 32768; // 32KB
    public static final int LINGER_MS = 5;
    public static final int BUFFER_MEMORY = 33554432; // 32MB
    public static final int MAX_IN_FLIGHT_REQUESTS = 5;
    public static final long RETRY_BACKOFF_MS = 100;
    public static final int RETRIES = 3;
    
    // Consumer Configuration
    public static final int MAX_POLL_RECORDS = 500;
    public static final long SESSION_TIMEOUT_MS = 30000;
    public static final long HEARTBEAT_INTERVAL_MS = 3000;
    public static final long AUTO_COMMIT_INTERVAL_MS = 5000;
    
    /**
     * Resolves the broker address from the environment, falling back to
     * {@link #FALLBACK_BOOTSTRAP_SERVERS}.
     */
    public static String defaultBootstrapServers() {
        String configured = System.getenv(BOOTSTRAP_SERVERS_ENV);
        return (configured == null || configured.isBlank())
            ? FALLBACK_BOOTSTRAP_SERVERS
            : configured;
    }

    /**
     * Resolves an explicitly supplied broker address, treating blank as
     * "inherit the framework default".
     *
     * <p>Annotation attributes default to the empty string so an unset value
     * follows {@link #defaultBootstrapServers()} rather than pinning a literal
     * at compile time.
     *
     * @param candidate address supplied by a caller or annotation, may be blank
     */
    public static String resolveBootstrapServers(String candidate) {
        return (candidate == null || candidate.isBlank())
            ? defaultBootstrapServers()
            : candidate;
    }

    /**
     * Resolves the producer compression codec from the environment, falling
     * back to {@link #FALLBACK_COMPRESSION_TYPE}.
     */
    public static String defaultCompressionType() {
        String configured = System.getenv(COMPRESSION_TYPE_ENV);
        return (configured == null || configured.isBlank())
            ? FALLBACK_COMPRESSION_TYPE
            : configured.trim().toLowerCase();
    }

    /**
     * Fails fast if the chosen codec's backing library is absent.
     *
     * <p>Without this the codec resolves lazily inside the producer's record
     * accumulator, surfacing as a {@code ClassNotFoundException} on the send
     * path long after startup, on a background thread, with no indication
     * that a dependency is missing.
     *
     * @param compressionType codec name as understood by Kafka
     * @throws IllegalStateException if the codec cannot be used
     */
    public static void validateCompressionType(String compressionType) {
        String required = switch (compressionType) {
            case "snappy" -> "org.xerial.snappy.SnappyOutputStream";
            case "lz4"    -> "net.jpountz.lz4.LZ4Factory";
            case "zstd"   -> "com.github.luben.zstd.ZstdOutputStreamNoFinalizer";
            default       -> null;   // none and gzip need nothing extra
        };

        if (required == null) {
            return;
        }

        try {
            Class.forName(required);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "compression.type=" + compressionType + " requires " + required
                    + ", which is not on the classpath. Add the library to the "
                    + "dependency list, or set " + COMPRESSION_TYPE_ENV
                    + " to gzip or none.", e);
        }
    }

    /**
     * Creates an optimized Producer configuration
     */
    public static Properties createProducerConfig() {
        Properties props = new Properties();
        
        // Basic Configuration
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, defaultBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Performance Optimizations
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, BATCH_SIZE);
        props.put(ProducerConfig.LINGER_MS_CONFIG, LINGER_MS);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, BUFFER_MEMORY);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, MAX_IN_FLIGHT_REQUESTS);
        
        // Reliability & Retries
        props.put(ProducerConfig.RETRIES_CONFIG, RETRIES);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, RETRY_BACKOFF_MS);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        
        // Compression for better throughput
        String compressionType = defaultCompressionType();
        validateCompressionType(compressionType);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compressionType);
        
        // Delivery timeout
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        
        // Request timeout
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        
        return props;
    }
    
    /**
     * Creates an optimized Consumer configuration
     */
    public static Properties createConsumerConfig(String groupId) {
        Properties props = new Properties();
        
        // Basic Configuration
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, defaultBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        
        // Group Management
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Performance Configuration
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, HEARTBEAT_INTERVAL_MS);
        
        // Auto-commit configuration
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"); // Manual commit for better control
        
        // Connection settings
        props.put(ConsumerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG, 540000);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        
        return props;
    }
    
    /**
     * Creates a topic with specified configuration
     */
    public static NewTopic createTopic(String topicName, int partitions, short replicationFactor) {
        return new NewTopic(topicName, partitions, replicationFactor);
    }
    
    /**
     * Creates topics with advanced configuration
     */
    public static NewTopic createAdvancedTopic(String topicName, int partitions, short replicationFactor) {
        NewTopic topic = new NewTopic(topicName, partitions, replicationFactor);
        
        // Configure topic properties
        topic.configs(Map.of(
            "cleanup.policy", "delete",           // Delete old messages
            "compression.type", defaultCompressionType(),
            "delete.retention.ms", "86400000",    // Keep deleted records for 1 day
            "file.delete.delay.ms", "60000",      // Wait 1 minute before deleting files
            "flush.ms", "1000",                   // Flush every second
            "index.interval.bytes", "4096",       // Index every 4KB
            "max.message.bytes", "1000000",       // 1MB max message size
            "min.insync.replicas", "1",           // Minimum in-sync replicas
            "retention.ms", "604800000",          // Keep messages for 7 days
            "segment.bytes", "1073741824"         // 1GB per segment
        ));
        
        return topic;
    }
}