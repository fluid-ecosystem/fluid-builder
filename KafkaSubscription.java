import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a Kafka message handler with full control over delivery, batching
 * and error-handling semantics.
 *
 * <p>The fuller counterpart to {@link KafkaListener}. Where
 * {@code @KafkaListener} covers the common case with three attributes, this
 * annotation exposes the full tuning surface: partitioning, batch consumption,
 * consumer-group timing, and dead letter routing.
 *
 * <p>Discovery is by reflection over classes whose file name ends in
 * {@code Service.java}, performed at startup by {@link Fluid}.
 *
 * <h2>Attribute support</h2>
 * Not every attribute is honoured yet. Those marked <em>not yet honoured</em>
 * below are accepted and validated but currently have no effect on runtime
 * behaviour; they are declared so the annotation surface stays stable while the
 * implementations land. Setting one is not an error, but it will not change how
 * messages are consumed until the referenced work is complete.
 *
 * @see KafkaListener
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KafkaSubscription {

    // ---------------------------------------------------------------- topic

    /** Topic this method consumes from. Required. */
    String topic();

    /** Consumer group this listener joins. Required. */
    String groupId();

    /**
     * Broker address for this listener.
     *
     * <p>Blank means inherit {@link KafkaConfig#defaultBootstrapServers()},
     * which reads {@code BOOTSTRAP_SERVERS} from the environment. Set this
     * only to pin one listener to a different broker.
     */
    String bootstrapServers() default "";

    // ------------------------------------------------------------ partitions

    /** Partition count used when the topic is created at startup. */
    int partitions() default 3;

    /** Replication factor used when the topic is created at startup. */
    short replicationFactor() default 1;

    // ----------------------------------------------------------------- batch

    /**
     * When {@code true}, records are delivered to the handler in batches
     * rather than one at a time.
     */
    boolean batchEnabled() default false;

    /** Target records per batch. <em>Not yet honoured.</em> */
    int batchSize() default 100;

    /** Maximum wait before dispatching a partial batch. <em>Not yet honoured.</em> */
    long batchTimeoutMs() default 1000;

    // -------------------------------------------------------------- consumer

    /**
     * Partition assignment strategy: {@code range}, {@code roundrobin} or
     * {@code sticky}. Unrecognised values fall back to {@code range}.
     */
    String partitionAssignmentStrategy() default "range";

    /**
     * Whether the consumer commits offsets automatically. Defaults to
     * {@code false} so offsets advance only after successful processing.
     */
    boolean enableAutoCommit() default false;

    /** Auto-commit interval. <em>Not yet honoured.</em> */
    long commitIntervalMs() default 5000;

    // ----------------------------------------------------------- performance

    /** Maximum records returned per poll. */
    int maxPollRecords() default 500;

    /** Consumer session timeout. */
    long sessionTimeoutMs() default 30000;

    /** Consumer heartbeat interval. Must be well below {@link #sessionTimeoutMs()}. */
    long heartbeatIntervalMs() default 3000;

    // -------------------------------------------------------- error handling

    /** Whether failed deliveries are retried. <em>Not yet honoured.</em> */
    boolean enableRetry() default true;

    /** Maximum retry attempts. <em>Not yet honoured.</em> */
    int maxRetries() default 3;

    /** Backoff between retries. <em>Not yet honoured.</em> */
    long retryBackoffMs() default 1000;

    // ---------------------------------------------------------- dead letters

    /**
     * Topic that undeliverable records are routed to. Has no effect unless
     * {@link #enableDeadLetterQueue()} is {@code true} and this is non-empty.
     */
    String deadLetterTopic() default "";

    /** Whether failed records are routed to {@link #deadLetterTopic()}. */
    boolean enableDeadLetterQueue() default false;

    // ------------------------------------------------------------ processing

    /**
     * Whether per-partition ordering is preserved across handler invocations.
     * <em>Not yet honoured</em> — the current dispatch path fans records out to
     * a shared pool, which cannot preserve order.
     */
    boolean preserveOrder() default false;

    /** Name of a method used to derive the partition key. <em>Not yet honoured.</em> */
    String keyExtractor() default "";
}
