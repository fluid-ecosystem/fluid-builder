import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced Kafka Producer with enhanced features:
 * - Custom partitioning strategies
 * - Batch processing with compression
 * - Comprehensive error handling and retries
 * - Performance monitoring and metrics
 * - Dead letter queue support
 */
public class AdvancedKafkaProducer {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedKafkaProducer.class);
    
    private final Map<String, KafkaProducer<String, String>> producers;
    private final AtomicLong totalMessagesSent;
    private final AtomicLong totalBytesSent;
    private final Properties baseConfig;
    
    public AdvancedKafkaProducer() {
        this.producers = new ConcurrentHashMap<>();
        this.totalMessagesSent = new AtomicLong(0);
        this.totalBytesSent = new AtomicLong(0);
        this.baseConfig = KafkaConfig.createProducerConfig();
    }
    
    public AdvancedKafkaProducer(Properties customConfig) {
        this.producers = new ConcurrentHashMap<>();
        this.totalMessagesSent = new AtomicLong(0);
        this.totalBytesSent = new AtomicLong(0);
        this.baseConfig = new Properties();
        this.baseConfig.putAll(KafkaConfig.createProducerConfig());
        this.baseConfig.putAll(customConfig);
    }
    
    /**
     * Send message with advanced features
     */
    public CompletableFuture<RecordMetadata> sendMessage(String topic, String key, String message) {
        return sendMessage(KafkaConfig.defaultBootstrapServers(), topic, key, message, null);
    }
    
    public CompletableFuture<RecordMetadata> sendMessage(String topic, String message) {
        return sendMessage(topic, null, message);
    }
    
    /**
     * Send message with custom partition
     */
    public CompletableFuture<RecordMetadata> sendMessage(String topic, String key, String message, Integer partition) {
        return sendMessage(KafkaConfig.defaultBootstrapServers(), topic, key, message, partition);
    }
    
    public CompletableFuture<RecordMetadata> sendMessage(String bootstrapServers, String topic, 
                                                         String key, String message, Integer partition) {
        KafkaProducer<String, String> producer = getOrCreateProducer(bootstrapServers);
        
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        
        try {
            ProducerRecord<String, String> record;
            if (partition != null) {
                record = new ProducerRecord<>(topic, partition, key, message);
            } else {
                record = new ProducerRecord<>(topic, key, message);
            }
            
            // Add callback for async processing
            producer.send(record, new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    if (exception != null) {
                        logger.error("Failed to send message to topic {}: {}", topic, exception.getMessage(), exception);
                        future.completeExceptionally(new KafkaProductionException("Failed to send message", exception));
                    } else {
                        totalMessagesSent.incrementAndGet();
                        totalBytesSent.addAndGet(message.getBytes().length);
                        logger.debug("Message sent successfully to topic {}, partition {}, offset {}", 
                                   topic, metadata.partition(), metadata.offset());
                        future.complete(metadata);
                    }
                }
            });
            
        } catch (Exception e) {
            logger.error("Error preparing message for topic {}: {}", topic, e.getMessage(), e);
            future.completeExceptionally(new KafkaProductionException("Error preparing message", e));
        }
        
        return future;
    }
    
    /**
     * Batch send messages for improved throughput
     */
    public CompletableFuture<Void> sendBatch(String topic, java.util.List<String> messages) {
        return sendBatch(KafkaConfig.defaultBootstrapServers(), topic, messages);
    }
    
    public CompletableFuture<Void> sendBatch(String bootstrapServers, String topic, 
                                           java.util.List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        
        KafkaProducer<String, String> producer = getOrCreateProducer(bootstrapServers);
        
        CompletableFuture<Void> future = new CompletableFuture<>();

        // The four-argument overload is (topic, key, message, partition), so
        // the previous call bound `message` to `partition`. Use the explicit
        // bootstrap-servers form with no partition preference.
        List<CompletableFuture<RecordMetadata>> futures = messages.stream()
            .map(message -> sendMessage(bootstrapServers, topic, null, message, null))
            .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new))
            .whenComplete((result, exception) -> {
            if (exception != null) {
                future.completeExceptionally(exception);
            } else {
                future.complete(null);
            }
        });
        
        return future;
    }
    
    /**
     * Send message with custom headers
     */
    public CompletableFuture<RecordMetadata> sendMessageWithHeaders(String topic, String key, String message, 
                                                                   Map<String, Object> headers) {
        return sendMessageWithHeaders(KafkaConfig.defaultBootstrapServers(), topic, key, message, headers);
    }
    
    public CompletableFuture<RecordMetadata> sendMessageWithHeaders(String bootstrapServers, String topic, 
                                                                    String key, String message, 
                                                                    Map<String, Object> headers) {
        KafkaProducer<String, String> producer = getOrCreateProducer(bootstrapServers);
        
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, message);
            
            // Add headers if provided
            if (headers != null && !headers.isEmpty()) {
                org.apache.kafka.common.header.internals.RecordHeader[] recordHeaders = 
                    headers.entrySet().stream()
                        .map(entry -> new org.apache.kafka.common.header.internals.RecordHeader(
                            entry.getKey(), entry.getValue().toString().getBytes()))
                        .toArray(org.apache.kafka.common.header.internals.RecordHeader[]::new);
                record = new ProducerRecord<>(topic, null, key, message, java.util.List.of(recordHeaders));
            }
            
            producer.send(record, new Callback() {
                @Override
                public void onCompletion(RecordMetadata metadata, Exception exception) {
                    if (exception != null) {
                        future.completeExceptionally(new KafkaProductionException("Failed to send message with headers", exception));
                    } else {
                        future.complete(metadata);
                    }
                }
            });
            
        } catch (Exception e) {
            future.completeExceptionally(new KafkaProductionException("Error preparing message with headers", e));
        }
        
        return future;
    }
    
    /**
     * Returns the producer for the given broker, creating it on first use.
     *
     * <p>The map is keyed by address and each entry is configured for that
     * address. Previously the lambda parameter was logged and then discarded,
     * so every entry was built from {@link #baseConfig} and therefore pointed
     * at the default broker regardless of what the caller asked for.
     */
    private KafkaProducer<String, String> getOrCreateProducer(String bootstrapServers) {
        String resolved = KafkaConfig.resolveBootstrapServers(bootstrapServers);
        return producers.computeIfAbsent(resolved, servers -> {
            logger.info("Creating new Kafka producer for bootstrap servers: {}", servers);
            Properties config = new Properties();
            config.putAll(baseConfig);
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
            return new KafkaProducer<>(config);
        });
    }
    
    /**
     * Get producer metrics for monitoring
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        metrics.put("totalMessagesSent", totalMessagesSent.get());
        metrics.put("totalBytesSent", totalBytesSent.get());
        metrics.put("activeProducers", producers.size());
        return metrics;
    }
    
    /**
     * Flush all producers
     */
    public void flush() {
        producers.values().forEach(KafkaProducer::flush);
        logger.info("Flushed all producers");
    }
    
    /**
     * Shutdown all producers gracefully
     */
    public void shutdown() {
        logger.info("Shutting down {} Kafka producers", producers.size());
        producers.values().forEach(producer -> {
            try {
                producer.flush();
                producer.close();
            } catch (Exception e) {
                logger.error("Error closing producer: {}", e.getMessage(), e);
            }
        });
        producers.clear();
        logger.info("All producers shut down successfully");
    }
}