import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the producer API: keys, explicit partitions, batches,
 * headers and error handling.
 */
public class MessageSender {
    
    private static final Logger logger = LoggerFactory.getLogger(MessageSender.class);
    
    private final MessageProducer producer;
    private final Random random;
    
    public MessageSender() {
        this.producer = new MessageProducer();
        this.random = new Random();
    }
    
    public void run() throws Exception {
        logger.info("🚀 Starting Message Sender");
        
        try {
            // Walk through each capability in turn
            demonstrateBasicMessaging();
            demonstrateKeyBasedPartitioning();
            demonstrateCustomPartitioning();
            demonstrateBatchProcessing();
            demonstrateHeaders();
            demonstrateErrorHandling();
            
            // Display final metrics
            displayMetrics();
            
        } finally {
            logger.info("📤 Shutting down message sender...");
            producer.shutdown();
        }
    }
    
    private void demonstrateBasicMessaging() throws Exception {
        logger.info("\n=== Basic Messaging ===");
        
        for (int i = 1; i <= 10; i++) {
            String message = String.format("Basic message %d", i);
            CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = 
                producer.sendMessage("demo-basic-topic", message);
            
            // Wait for completion (in production, you'd handle this asynchronously)
            try {
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(1, TimeUnit.SECONDS);
                logger.info("Sent basic message {} to partition {} at offset {}", 
                           i, metadata.partition(), metadata.offset());
            } catch (Exception e) {
                logger.warn("Failed to send basic message {}: {}", i, e.getMessage());
            }
        }
    }
    
    private void demonstrateKeyBasedPartitioning() throws Exception {
        logger.info("\n=== Key-Based Partitioning ===");
        
        String[] keys = {"user1", "user2", "user3", "user4", "user5"};
        
        for (int i = 1; i <= 20; i++) {
            String key = keys[i % keys.length];
            String message = String.format("Key-based message %d for user %s", i, key);
            
            CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = 
                producer.sendMessage("demo-key-topic", key, message);
            
            try {
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(1, TimeUnit.SECONDS);
                logger.info("Sent key-based message {} with key '{}' to partition {}", 
                           i, key, metadata.partition());
            } catch (Exception e) {
                logger.warn("Failed to send key-based message {}: {}", i, e.getMessage());
            }
        }
    }
    
    private void demonstrateCustomPartitioning() throws Exception {
        logger.info("\n=== Custom Partitioning ===");
        
        // Demonstrate sending to specific partitions
        for (int partition = 0; partition < 3; partition++) {
            for (int i = 1; i <= 5; i++) {
                String message = String.format("Custom partition %d message %d", partition, i);
                
                CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = 
                    producer.sendMessage("demo-partition-topic", null, message, partition);
                
                try {
                    org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(1, TimeUnit.SECONDS);
                    logger.info("Sent custom partition message {} to partition {} at offset {}", 
                               i, metadata.partition(), metadata.offset());
                } catch (Exception e) {
                    logger.warn("Failed to send custom partition message: {}", e.getMessage());
                }
            }
        }
    }
    
    private void demonstrateBatchProcessing() throws Exception {
        logger.info("\n=== Batch Processing ===");
        
        List<String> batch1 = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            batch1.add(String.format("Batch 1 message %d", i));
        }
        
        List<String> batch2 = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            batch2.add(String.format("Batch 2 message %d", i));
        }
        
        // Send batches
        CompletableFuture<Void> future1 = producer.sendBatch("demo-batch-topic", batch1);
        CompletableFuture<Void> future2 = producer.sendBatch("demo-batch-topic", batch2);
        
        try {
            future1.get(2, TimeUnit.SECONDS);
            future2.get(2, TimeUnit.SECONDS);
            logger.info("Successfully sent both batches");
        } catch (Exception e) {
            logger.warn("Failed to send batches: {}", e.getMessage());
        }
    }
    
    private void demonstrateHeaders() throws Exception {
        logger.info("\n=== Message Headers ===");
        
        Map<String, Object> headers = new HashMap<>();
        headers.put("message-type", "notification");
        headers.put("priority", "high");
        headers.put("source", "message-sender");
        headers.put("timestamp", System.currentTimeMillis());
        
        for (int i = 1; i <= 5; i++) {
            String message = String.format("Header message %d", i);
            
            CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = 
                producer.sendMessageWithHeaders("demo-headers-topic", "header-key-" + i, message, headers);
            
            try {
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(1, TimeUnit.SECONDS);
                logger.info("Sent header message {} with headers to partition {}", i, metadata.partition());
            } catch (Exception e) {
                logger.warn("Failed to send header message {}: {}", i, e.getMessage());
            }
        }
    }
    
    private void demonstrateErrorHandling() throws Exception {
        logger.info("\n=== Error Handling ===");
        
        // Demonstrate various scenarios that might cause errors
        List<String> scenarios = Arrays.asList(
            "normal message",
            "message with special chars: ñáéíóú",
            "very long message: " + "x".repeat(1000),
            "unicode message: 你好世界 🌍",
            "json message: {\"type\":\"test\",\"value\":123}"
        );
        
        for (int i = 0; i < scenarios.size(); i++) {
            String scenario = scenarios.get(i);
            CompletableFuture<org.apache.kafka.clients.producer.RecordMetadata> future = 
                producer.sendMessage("demo-error-topic", "scenario-" + i, scenario);
            
            try {
                org.apache.kafka.clients.producer.RecordMetadata metadata = future.get(1, TimeUnit.SECONDS);
                logger.info("Sent error scenario {} to partition {}", i, metadata.partition());
            } catch (Exception e) {
                logger.warn("Error scenario {} failed as expected: {}", i, e.getMessage());
            }
        }
    }
    
    private void displayMetrics() {
        logger.info("\n=== Producer Metrics ===");
        
        Map<String, Object> metrics = producer.getMetrics();
        
        metrics.forEach((key, value) -> {
            if (value instanceof Number) {
                logger.info("📊 {}: {}", key, value);
            }
        });
        
        logger.info("\n🎯 Message sending completed successfully!");
        logger.info("📈 Total messages sent: {}", metrics.get("totalMessagesSent"));
        logger.info("📦 Total bytes sent: {}", metrics.get("totalBytesSent"));
        logger.info("🔧 Active producers: {}", metrics.get("activeProducers"));
    }
    
    public static void main(String[] args) throws Exception {
        MessageSender sender = new MessageSender();
        sender.run();
    }
}