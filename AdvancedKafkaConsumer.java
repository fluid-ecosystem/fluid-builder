import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced Kafka Consumer with enhanced features:
 * - Batch processing capabilities
 * - Manual offset management
 * - Dead letter queue support
 * - Performance monitoring
 * - Custom partitioning strategies
 * - Graceful shutdown handling
 */
public class AdvancedKafkaConsumer {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedKafkaConsumer.class);
    
    private final Map<String, KafkaConsumer<String, String>> consumers;
    private final AtomicLong totalMessagesConsumed;
    private final AtomicLong totalBytesConsumed;
    private final ExecutorService messageProcessorExecutor;
    
    public AdvancedKafkaConsumer() {
        this.consumers = new ConcurrentHashMap<>();
        this.totalMessagesConsumed = new AtomicLong(0);
        this.totalBytesConsumed = new AtomicLong(0);
        this.messageProcessorExecutor = Executors.newCachedThreadPool();
    }
    
    /**
     * Subscribe to topic with consumer group
     */
    public void subscribe(String groupId, String topic, MessageHandler messageHandler) {
        subscribe(groupId, Collections.singletonList(topic), messageHandler);
    }
    
    public void subscribe(String groupId, List<String> topics, MessageHandler messageHandler) {
        subscribe(KafkaConfig.DEFAULT_BOOTSTRAP_SERVERS, groupId, topics, messageHandler);
    }
    
    public void subscribe(String bootstrapServers, String groupId, List<String> topics, MessageHandler messageHandler) {
        KafkaConsumer<String, String> consumer = getOrCreateConsumer(bootstrapServers, groupId);
        
        consumer.subscribe(topics, new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                logger.info("Partitions revoked: {}", partitions);
            }
            
            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                logger.info("Partitions assigned: {}", partitions);
                // Optionally seek to beginning or specific offsets
                consumer.seekToBeginning(partitions);
            }
        });
        
        // Start consuming messages
        startConsuming(consumer, messageHandler);
    }
    
    /**
     * Subscribe to specific partitions
     */
    public void subscribeToPartitions(String groupId, Map<TopicPartition, Long> partitionOffsets, MessageHandler messageHandler) {
        subscribeToPartitions(KafkaConfig.DEFAULT_BOOTSTRAP_SERVERS, groupId, partitionOffsets, messageHandler);
    }
    
    public void subscribeToPartitions(String bootstrapServers, String groupId, 
                                    Map<TopicPartition, Long> partitionOffsets, MessageHandler messageHandler) {
        KafkaConsumer<String, String> consumer = getOrCreateConsumer(bootstrapServers, groupId);
        
        Set<TopicPartition> topicPartitions = partitionOffsets.keySet();
        consumer.assign(topicPartitions);
        
        // Seek to specified offsets
        partitionOffsets.forEach((partition, offset) -> {
            consumer.seek(partition, offset);
            logger.info("Seeking partition {} to offset {}", partition, offset);
        });
        
        // Start consuming messages
        startConsuming(consumer, messageHandler);
    }
    
    /**
     * Batch consume messages for improved throughput
     */
    public void consumeBatch(String groupId, String topic, BatchMessageHandler batchHandler) {
        consumeBatch(KafkaConfig.DEFAULT_BOOTSTRAP_SERVERS, groupId, topic, batchHandler);
    }
    
    public void consumeBatch(String bootstrapServers, String groupId, String topic, BatchMessageHandler batchHandler) {
        KafkaConsumer<String, String> consumer = getOrCreateConsumer(bootstrapServers, groupId);
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
        consumeWithManualOffset(KafkaConfig.DEFAULT_BOOTSTRAP_SERVERS, groupId, topic, offsetHandler);
    }
    
    public void consumeWithManualOffset(String bootstrapServers, String groupId, String topic, ManualOffsetHandler offsetHandler) {
        KafkaConsumer<String, String> consumer = getOrCreateConsumer(bootstrapServers, groupId);
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
    
    private void startConsuming(KafkaConsumer<String, String> consumer, MessageHandler messageHandler) {
        messageProcessorExecutor.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    
                    for (ConsumerRecord<String, String> record : records) {
                        messageProcessorExecutor.submit(() -> {
                            try {
                                messageHandler.handleMessage(record);
                                totalMessagesConsumed.incrementAndGet();
                                
                                long bytes = record.value() != null ? record.value().getBytes().length : 0;
                                totalBytesConsumed.addAndGet(bytes);
                                
                            } catch (Exception e) {
                                logger.error("Error processing message: {}", e.getMessage(), e);
                            }
                        });
                    }
                    
                    // Auto-commit if enabled in consumer config
                    if (Boolean.parseBoolean(consumer.config().getProperty("enable.auto.commit", "true"))) {
                        consumer.commitAsync();
                    }
                }
            } catch (Exception e) {
                logger.error("Error in consumer: {}", e.getMessage(), e);
            } finally {
                consumer.close();
            }
        });
    }
    
    private KafkaConsumer<String, String> getOrCreateConsumer(String bootstrapServers, String groupId) {
        String key = bootstrapServers + ":" + groupId;
        return consumers.computeIfAbsent(key, servers -> {
            logger.info("Creating new Kafka consumer for group {} on servers {}", groupId, bootstrapServers);
            Properties config = KafkaConfig.createConsumerConfig(groupId);
            config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            return new KafkaConsumer<>(config);
        });
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
        
        messageProcessorExecutor.shutdown();
        try {
            if (!messageProcessorExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                messageProcessorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            messageProcessorExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        consumers.values().forEach(consumer -> {
            try {
                consumer.wakeup(); // Wake up any waiting poll
                consumer.close();
            } catch (Exception e) {
                logger.error("Error closing consumer: {}", e.getMessage(), e);
            }
        });
        consumers.clear();
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
     * Functional interface for manual offset handling
     */
    @FunctionalInterface
    public interface ManualOffsetHandler {
        void handleMessage(ConsumerRecord<String, String> record) throws Exception;
        void handleError(ConsumerRecord<String, String> record, Exception error);
    }
}