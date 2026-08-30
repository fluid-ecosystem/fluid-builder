package com.fluid.enhanced.examples;

import com.fluid.enhanced.annotations.EnhancedKafkaListener;
import com.fluid.enhanced.annotations.PartitionStrategy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced Example Service demonstrating enhanced Fluid Framework features
 * 
 * Features demonstrated:
 * - Batch processing
 * - Dead letter queue handling
 * - Custom partitioning
 * - Error handling with retries
 * - Performance monitoring
 * - Message enrichment
 */
@PartitionStrategy(PartitionStrategy.Strategy.KEY_BASED)
public class AdvancedOrderProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(AdvancedOrderProcessingService.class);
    
    // Metrics tracking
    private final AtomicLong totalOrdersProcessed = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final Map<String, Long> orderTypeCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> errorTypeCounts = new ConcurrentHashMap<>();
    
    /**
     * Basic order processing with advanced features
     */
    @EnhancedKafkaListener(
        topic = "advanced-orders",
        groupId = "advanced-order-processors",
        bootstrapServers = "localhost:9092",
        partitions = 5,
        replicationFactor = 1,
        batchEnabled = false,
        enableRetry = true,
        maxRetries = 3,
        enableDeadLetterQueue = true,
        deadLetterTopic = "order-errors",
        maxPollRecords = 100,
        sessionTimeoutMs = 30000,
        heartbeatIntervalMs = 3000,
        enableAutoCommit = false
    )
    public void processOrder(String orderData) {
        try {
            logger.info("Processing order: {}", orderData);
            
            // Parse and validate order
            Order order = parseOrder(orderData);
            validateOrder(order);
            
            // Process the order
            processOrderLogic(order);
            
            // Update metrics
            totalOrdersProcessed.incrementAndGet();
            orderTypeCounts.merge(order.getType(), 1L, Long::sum);
            
            logger.info("Successfully processed order {} of type {}", order.getId(), order.getType());
            
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            String errorType = e.getClass().getSimpleName();
            errorTypeCounts.merge(errorType, 1L, Long::sum);
            
            logger.error("Error processing order {}: {}", orderData, e.getMessage(), e);
            throw e; // This will trigger retry mechanism and potentially dead letter queue
        }
    }
    
    /**
     * Batch processing for high-throughput scenarios
     */
    @EnhancedKafkaListener(
        topic = "batch-orders",
        groupId = "batch-order-processors",
        bootstrapServers = "localhost:9092",
        partitions = 10,
        replicationFactor = 1,
        batchEnabled = true,
        batchSize = 50,
        batchTimeoutMs = 5000,
        enableDeadLetterQueue = true,
        deadLetterTopic = "batch-order-errors"
    )
    public void processBatchOrders(List<ConsumerRecord<String, String>> batchRecords) {
        logger.info("Processing batch of {} orders", batchRecords.size());
        
        try {
            // Process each order in the batch
            for (ConsumerRecord<String, String> record : batchRecords) {
                try {
                    Order order = parseOrder(record.value());
                    validateOrder(order);
                    processOrderLogic(order);
                    totalOrdersProcessed.incrementAndGet();
                    orderTypeCounts.merge(order.getType(), 1L, Long::sum);
                    
                } catch (Exception e) {
                    logger.error("Error processing order in batch from topic {}, partition {}, offset {}: {}", 
                               record.topic(), record.partition(), record.offset(), e.getMessage());
                    
                    totalErrors.incrementAndGet();
                    String errorType = e.getClass().getSimpleName();
                    errorTypeCounts.merge(errorType, 1L, Long::sum);
                    
                    // Individual error handling (could send to dead letter queue)
                    handleIndividualError(record, e);
                }
            }
            
            logger.info("Successfully processed batch of {} orders", batchRecords.size());
            
        } catch (Exception e) {
            logger.error("Critical error in batch processing: {}", e.getMessage(), e);
            throw e; // This will send the entire batch to dead letter queue
        }
    }
    
    /**
     * High-priority order processing with custom partitioning
     */
    @EnhancedKafkaListener(
        topic = "priority-orders",
        groupId = "priority-order-processors",
        bootstrapServers = "localhost:9092",
        partitions = 3,
        replicationFactor = 1,
        preserveOrder = true,
        partitionAssignmentStrategy = "sticky",
        enableRetry = true,
        maxRetries = 5,
        retryBackoffMs = 1000
    )
    public void processPriorityOrder(ConsumerRecord<String, String> record) {
        String key = record.key();
        String value = record.value();
        
        logger.info("Processing priority order with key: {}", key);
        
        try {
            Order order = parseOrder(value);
            
            // Enhanced validation for priority orders
            validatePriorityOrder(order);
            
            // Process with priority logic
            processPriorityOrderLogic(order);
            
            totalOrdersProcessed.incrementAndGet();
            orderTypeCounts.merge("PRIORITY", 1L, Long::sum);
            
            logger.info("Successfully processed priority order {}", order.getId());
            
        } catch (Exception e) {
            totalErrors.incrementAndGet();
            errorTypeCounts.merge("PRIORITY_ERROR", 1L, Long::sum);
            
            logger.error("Error processing priority order {}: {}", key, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Customer service events with custom message handling
     */
    @EnhancedKafkaListener(
        topic = "customer-events",
        groupId = "customer-service",
        bootstrapServers = "localhost:9092",
        partitions = 2,
        replicationFactor = 1,
        keyExtractor = "extractCustomerId"
    )
    public void handleCustomerEvent(String eventData, String customerId) {
        logger.info("Handling customer event for customer {}: {}", customerId, eventData);
        
        try {
            CustomerEvent event = parseCustomerEvent(eventData);
            
            // Process different types of customer events
            switch (event.getType()) {
                case REGISTRATION:
                    handleCustomerRegistration(event);
                    break;
                case PROFILE_UPDATE:
                    handleProfileUpdate(event);
                    break;
                case PREFERENCES_CHANGE:
                    handlePreferencesChange(event);
                    break;
                default:
                    logger.warn("Unknown customer event type: {}", event.getType());
            }
            
        } catch (Exception e) {
            logger.error("Error handling customer event for {}: {}", customerId, e.getMessage(), e);
            throw e;
        }
    }
    
    // Helper methods
    
    private Order parseOrder(String orderData) {
        // Simplified parsing - in reality, this would use JSON/XML parsing
        String[] parts = orderData.split(",");
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid order data format");
        }
        
        return new Order(
            parts[0], // ID
            parts[1], // Type
            Double.parseDouble(parts[2]) // Amount
        );
    }
    
    private void validateOrder(Order order) {
        if (order.getId() == null || order.getId().trim().isEmpty()) {
            throw new IllegalArgumentException("Order ID is required");
        }
        if (order.getAmount() <= 0) {
            throw new IllegalArgumentException("Order amount must be positive");
        }
    }
    
    private void validatePriorityOrder(Order order) {
        validateOrder(order);
        if (!"PRIORITY".equals(order.getType())) {
            throw new IllegalArgumentException("Not a priority order");
        }
    }
    
    private void processOrderLogic(Order order) {
        // Simulate order processing
        logger.debug("Executing order logic for order {}", order.getId());
        
        // Simulate some processing time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Here you would implement actual business logic
        // - Database updates
        // - External API calls
        // - Payment processing
        // - Inventory management
        // - Notifications
    }
    
    private void processPriorityOrderLogic(Order order) {
        logger.debug("Executing priority order logic for order {}", order.getId());
        // Enhanced processing for priority orders
        processOrderLogic(order);
        
        // Additional priority-specific logic
        try {
            Thread.sleep(5); // Priority orders get faster processing
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void handleIndividualError(ConsumerRecord<String, String> record, Exception e) {
        // Handle individual message errors (e.g., send to individual dead letter queue)
        logger.error("Individual error handling for record from topic {}, partition {}, offset {}", 
                   record.topic(), record.partition(), record.offset());
    }
    
    private CustomerEvent parseCustomerEvent(String eventData) {
        // Simplified parsing
        String[] parts = eventData.split(",");
        return new CustomerEvent(parts[0], CustomerEvent.EventType.valueOf(parts[1]));
    }
    
    private void handleCustomerRegistration(CustomerEvent event) {
        logger.info("Handling customer registration event: {}", event.getData());
        // Registration-specific logic
    }
    
    private void handleProfileUpdate(CustomerEvent event) {
        logger.info("Handling profile update event: {}", event.getData());
        // Profile update logic
    }
    
    private void handlePreferencesChange(CustomerEvent event) {
        logger.info("Handling preferences change event: {}", event.getData());
        // Preferences change logic
    }
    
    // Public methods for monitoring
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        metrics.put("totalOrdersProcessed", totalOrdersProcessed.get());
        metrics.put("totalErrors", totalErrors.get());
        metrics.put("orderTypeCounts", new ConcurrentHashMap<>(orderTypeCounts));
        metrics.put("errorTypeCounts", new ConcurrentHashMap<>(errorTypeCounts));
        return metrics;
    }
    
    public void resetMetrics() {
        totalOrdersProcessed.set(0);
        totalErrors.set(0);
        orderTypeCounts.clear();
        errorTypeCounts.clear();
    }
    
    // Inner classes
    public static class Order {
        private final String id;
        private final String type;
        private final double amount;
        
        public Order(String id, String type, double amount) {
            this.id = id;
            this.type = type;
            this.amount = amount;
        }
        
        public String getId() { return id; }
        public String getType() { return type; }
        public double getAmount() { return amount; }
        
        @Override
        public String toString() {
            return String.format("Order{id='%s', type='%s', amount=%.2f}", id, type, amount);
        }
    }
    
    public static class CustomerEvent {
        private final String data;
        private final EventType type;
        
        public CustomerEvent(String data, EventType type) {
            this.data = data;
            this.type = type;
        }
        
        public String getData() { return data; }
        public EventType getType() { return type; }
        
        public enum EventType {
            REGISTRATION,
            PROFILE_UPDATE,
            PREFERENCES_CHANGE,
            PURCHASE,
            REFUND
        }
    }
}