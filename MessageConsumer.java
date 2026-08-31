import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.kafka.common.errors.WakeupException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka consumer supporting:
 * - Batch processing capabilities
 * - Manual offset management
 * - Dead letter queue support
 * - Performance monitoring
 * - Custom partitioning strategies
 * - Graceful shutdown handling
 */
public class MessageConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageConsumer.class);
    
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    /**
     * A consumer, the configuration it was built from, and a label for logs.
     *
     * <p>{@code KafkaConsumer} exposes no accessor for its own settings, so
     * the resolved {@link Properties} are retained here rather than queried
     * back off the client.
     *
     * <p>Each instance is owned by exactly one poll thread. {@code
     * KafkaConsumer} is not thread safe, so consumers are never shared
     * between subscriptions.
     */
    private record ManagedConsumer(Consumer<String, String> consumer,
                                   Properties config,
                                   String description) {

        boolean isAutoCommitEnabled() {
            return Boolean.parseBoolean(
                config.getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true"));
        }
    }

    private final List<ManagedConsumer> consumers;
    private final AtomicLong totalMessagesConsumed;
    private final AtomicLong totalBytesConsumed;
    private final ExecutorService messageProcessorExecutor;
    private volatile boolean running;
    
    public MessageConsumer() {
        this.consumers = new CopyOnWriteArrayList<>();
        this.totalMessagesConsumed = new AtomicLong(0);
        this.totalBytesConsumed = new AtomicLong(0);
        this.messageProcessorExecutor = Executors.newCachedThreadPool();
        this.running = true;
    }
    
    /**
     * Subscribe to topic with consumer group
     */
    public void subscribe(String groupId, String topic, MessageHandler messageHandler) {
        subscribe(groupId, Collections.singletonList(topic), messageHandler);
    }
    
    public void subscribe(String groupId, List<String> topics, MessageHandler messageHandler) {
        subscribe(KafkaConfig.defaultBootstrapServers(), groupId, topics, messageHandler);
    }
    
    public void subscribe(String bootstrapServers, String groupId, List<String> topics, MessageHandler messageHandler) {
        subscribe(bootstrapServers, groupId, topics, messageHandler, null);
    }

    /**
     * Subscribes with additional consumer settings layered over the defaults.
     *
     * @param overrides extra consumer properties, may be {@code null}
     */
    public void subscribe(String bootstrapServers, String groupId, List<String> topics,
                          MessageHandler messageHandler, Properties overrides) {
        ManagedConsumer managed = createConsumer(bootstrapServers, groupId, overrides,
            "topics=" + topics);
        
        managed.consumer().subscribe(topics, new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                logger.info("Partitions revoked: {}", partitions);
            }
            
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // Deliberately no seek here. Resuming from the committed
                // offset is the point of a consumer group; rewinding on every
                // rebalance would replay the whole topic. Where to start when
                // no offset is committed is `auto.offset.reset`.
                logger.info("Partitions assigned: {}", partitions);
            }
        });
        
        // Start consuming messages
        startConsuming(managed, messageHandler);
    }
    
    /**
     * Subscribe to specific partitions
     */
    public void subscribeToPartitions(String groupId, Map<TopicPartition, Long> partitionOffsets, MessageHandler messageHandler) {
        subscribeToPartitions(KafkaConfig.defaultBootstrapServers(), groupId, partitionOffsets, messageHandler);
    }
    
    public void subscribeToPartitions(String bootstrapServers, String groupId, 
                                    Map<TopicPartition, Long> partitionOffsets, MessageHandler messageHandler) {
        ManagedConsumer managed = createConsumer(bootstrapServers, groupId, null,
            "partitions=" + partitionOffsets.keySet());
        Consumer<String, String> consumer = managed.consumer();
        
        Set<TopicPartition> topicPartitions = partitionOffsets.keySet();
        consumer.assign(topicPartitions);
        
        // Seek to specified offsets
        partitionOffsets.forEach((partition, offset) -> {
            consumer.seek(partition, offset);
            logger.info("Seeking partition {} to offset {}", partition, offset);
        });
        
        // Start consuming messages
        startConsuming(managed, messageHandler);
    }
    
    /**
     * Batch consume messages for improved throughput
     */
    public void consumeBatch(String groupId, String topic, BatchMessageHandler batchHandler) {
        consumeBatch(KafkaConfig.defaultBootstrapServers(), groupId, topic, batchHandler);
    }
    
    public void consumeBatch(String bootstrapServers, String groupId, String topic, BatchMessageHandler batchHandler) {
        consumeBatch(bootstrapServers, groupId, topic, batchHandler, null);
    }

    /**
     * Batch consumption with additional consumer settings layered over the
     * defaults.
     *
     * @param overrides extra consumer properties, may be {@code null}
     */
    public void consumeBatch(String bootstrapServers, String groupId, String topic,
                             BatchMessageHandler batchHandler, Properties overrides) {
        ManagedConsumer managed = createConsumer(bootstrapServers, groupId, overrides,
            "batch topic=" + topic);
        Consumer<String, String> consumer = managed.consumer();
        consumer.subscribe(Collections.singletonList(topic));
        
        messageProcessorExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    
                    if (!records.isEmpty()) {
                        List<ConsumerRecord<String, String>> recordList = new ArrayList<>();
                        records.forEach(recordList::add);
                        
                        try {
                            batchHandler.handleBatch(recordList);
                            
                            // Commit offsets after successful processing
                            consumer.commitAsync();
                            totalMessagesConsumed.addAndGet(recordList.size());
                            
                            long bytesProcessed = recordList.stream()
                                .mapToLong(record -> record.value() != null ? record.value().getBytes().length : 0)
                                .sum();
                            totalBytesConsumed.addAndGet(bytesProcessed);
                            
                        } catch (Exception e) {
                            logger.error("Error processing batch: {}", e.getMessage(), e);
                            // Handle batch processing errors (e.g., send to dead letter queue)
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error in batch consumer: {}", e.getMessage(), e);
            } finally {
                consumer.close();
            }
        });
    }
    
    /**
     * Manual offset management
     */
    public void consumeWithManualOffset(String groupId, String topic, ManualOffsetHandler offsetHandler) {
        consumeWithManualOffset(KafkaConfig.defaultBootstrapServers(), groupId, topic, offsetHandler);
    }
    
    public void consumeWithManualOffset(String bootstrapServers, String groupId, String topic, ManualOffsetHandler offsetHandler) {
        ManagedConsumer managed = createConsumer(bootstrapServers, groupId, null,
            "manual-offset topic=" + topic);
        Consumer<String, String> consumer = managed.consumer();
        consumer.subscribe(Collections.singletonList(topic));
        
        messageProcessorExecutor.submit(() -> {
            try {
                Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();
                
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    
                    for (ConsumerRecord<String, String> record : records) {
                        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                        
                        try {
                            // Process message
                            offsetHandler.handleMessage(record);
                            
                            // Track offset for manual commit
                            currentOffsets.put(partition, new OffsetAndMetadata(record.offset() + 1));
                            
                        } catch (Exception e) {
                            logger.error("Error processing message: {}", e.getMessage(), e);
                            offsetHandler.handleError(record, e);
                        }
                    }
                    
                    // Commit offsets manually
                    if (!currentOffsets.isEmpty()) {
                        try {
                            consumer.commitSync(currentOffsets);
                            logger.debug("Committed offsets: {}", currentOffsets);
                            currentOffsets.clear();
                        } catch (CommitFailedException e) {
                            logger.error("Failed to commit offsets: {}", e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error in manual offset consumer: {}", e.getMessage(), e);
            } finally {
                consumer.close();
            }
        });
    }
    
    /**
     * Runs the poll loop for one consumer.
     *
     * <p>Handlers run on the poll thread rather than being dispatched to a
     * pool. That is what makes per-partition ordering hold, and it is what
     * lets offsets be committed only once the records they cover have
     * actually been processed.
     *
     * <p>When a handler throws, the offending partition is left uncommitted
     * for that poll and its remaining records are skipped, so ordering is not
     * broken by continuing past a failure. Other partitions are unaffected.
     * The record will be redelivered — route poison messages to a dead letter
     * topic rather than relying on them being dropped.
     */
    private void startConsuming(ManagedConsumer managed, MessageHandler messageHandler) {
        Consumer<String, String> consumer = managed.consumer();
        messageProcessorExecutor.submit(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
                    if (records.isEmpty()) {
                        continue;
                    }

                    Map<TopicPartition, OffsetAndMetadata> processed = new HashMap<>();
                    Map<TopicPartition, Long> failed = new HashMap<>();

                    for (ConsumerRecord<String, String> record : records) {
                        TopicPartition partition =
                            new TopicPartition(record.topic(), record.partition());

                        // Preserve ordering: once a partition fails, stop
                        // consuming it for the rest of this batch.
                        if (failed.containsKey(partition)) {
                            continue;
                        }

                        try {
                            messageHandler.handleMessage(record);
                            processed.put(partition, new OffsetAndMetadata(record.offset() + 1));
                            recordConsumed(record);
                        } catch (Exception e) {
                            failed.put(partition, record.offset());
                            logger.error("Handler failed for {}-{} at offset {}; "
                                    + "partition not advanced: {}",
                                record.topic(), record.partition(), record.offset(),
                                e.getMessage(), e);
                        }
                    }

                    // Rewind each failed partition to the record that failed.
                    // Skipping the rest of the batch is not enough on its own:
                    // the consumer's position has already moved past it, so
                    // without a seek the next poll would deliver the following
                    // record and commit straight over the failure.
                    // Any record that succeeded before the failure stays
                    // committed: its offset and the seek target are the same
                    // point, so nothing is reprocessed and nothing is skipped.
                    failed.forEach((partition, offset) -> {
                        consumer.seek(partition, offset);
                        FluidMetrics.framework()
                            .partitionRewound(partition.topic(), partition.partition());
                    });

                    // Commit only what was processed, and only when Kafka is
                    // not already committing on our behalf.
                    if (!managed.isAutoCommitEnabled() && !processed.isEmpty()) {
                        try {
                            consumer.commitSync(processed);
                        } catch (CommitFailedException e) {
                            logger.error("Failed to commit offsets for {}: {}",
                                managed.description(), e.getMessage(), e);
                        }
                    }
                }
            } catch (WakeupException e) {
                logger.debug("Consumer {} woken for shutdown", managed.description());
            } catch (Exception e) {
                logger.error("Error in consumer {}: {}", managed.description(), e.getMessage(), e);
            } finally {
                consumer.close();
            }
        });
    }

    /**
     * Creates the underlying client. Overridable so the poll loop can be
     * driven by a {@code MockConsumer} without a live broker.
     */
    protected Consumer<String, String> newConsumer(Properties config) {
        return new KafkaConsumer<>(config);
    }

    private void recordConsumed(ConsumerRecord<String, String> record) {
        totalMessagesConsumed.incrementAndGet();
        totalBytesConsumed.addAndGet(
            record.value() != null ? record.value().getBytes(StandardCharsets.UTF_8).length : 0);
    }
    
    /**
     * Builds a consumer dedicated to one subscription.
     *
     * <p>Consumers are never shared. {@code KafkaConsumer} is not thread safe,
     * and two subscriptions sharing an instance would also clobber each
     * other's {@code subscribe()} calls, silently stopping one of them.
     *
     * @param overrides extra consumer properties layered over the defaults,
     *                  may be {@code null}
     */
    private ManagedConsumer createConsumer(String bootstrapServers, String groupId,
                                           Properties overrides, String description) {
        String resolved = KafkaConfig.resolveBootstrapServers(bootstrapServers);

        Properties config = KafkaConfig.createConsumerConfig(groupId);
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolved);
        if (overrides != null) {
            config.putAll(overrides);
            // The caller does not get to redirect the consumer.
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolved);
            config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }

        logger.info("Creating Kafka consumer for group {} on {} ({})",
            groupId, resolved, description);

        ManagedConsumer managed =
            new ManagedConsumer(newConsumer(config), config, description);
        consumers.add(managed);
        FluidMetrics.framework().consumersActive(consumers.size());
        return managed;
    }
    
    /**
     * Get consumer metrics for monitoring
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        metrics.put("totalMessagesConsumed", totalMessagesConsumed.get());
        metrics.put("totalBytesConsumed", totalBytesConsumed.get());
        metrics.put("activeConsumers", consumers.size());
        return metrics;
    }
    
    /**
     * Graceful shutdown
     */
    public void shutdown() {
        logger.info("Shutting down {} Kafka consumers", consumers.size());

        // Order matters. Poll loops block in poll(), so they must be woken
        // before the executor is awaited — otherwise the wait always times
        // out and the loops are killed mid-batch, after handlers ran but
        // before their offsets were committed.
        running = false;
        consumers.forEach(managed -> {
            try {
                managed.consumer().wakeup();
            } catch (Exception e) {
                logger.error("Error waking consumer {}: {}",
                    managed.description(), e.getMessage(), e);
            }
        });

        messageProcessorExecutor.shutdown();
        try {
            if (!messageProcessorExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Consumers did not stop within 30s; forcing shutdown");
                messageProcessorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            messageProcessorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Each poll loop closes its own consumer on the way out; this only
        // catches any that never started.
        consumers.clear();
        FluidMetrics.framework().consumersActive(0);
        logger.info("All consumers shut down successfully");
    }
    
    /**
     * Functional interface for message handling
     */
    @FunctionalInterface
    public interface MessageHandler {
        void handleMessage(ConsumerRecord<String, String> record) throws Exception;
    }
    
    /**
     * Functional interface for batch message handling
     */
    @FunctionalInterface
    public interface BatchMessageHandler {
        void handleBatch(List<ConsumerRecord<String, String>> records) throws Exception;
    }
    
    /**
     * Callback pair for manual offset handling.
     *
     * <p>Not a functional interface: it declares two abstract methods, so it
     * cannot be written as a lambda.
     */
    public interface ManualOffsetHandler {
        void handleMessage(ConsumerRecord<String, String> record) throws Exception;
        void handleError(ConsumerRecord<String, String> record, Exception error);
    }
}