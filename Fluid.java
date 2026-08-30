import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Fluid Framework with Advanced Kafka Features
 * 
 * Features:
 * - Automatic topic creation with custom configurations
 * - Advanced producer/consumer management
 * - Batch processing capabilities
 * - Dead letter queue support
 * - Performance monitoring
 * - Graceful shutdown handling
 * - Multiple consumer groups
 * - Custom partitioning strategies
 */
public class Fluid {
    
    private static final Logger logger = LoggerFactory.getLogger(Fluid.class);
    
    private final AdvancedKafkaProducer producer;
    private final AdvancedKafkaConsumer consumer;
    /**
     * Opened on first use rather than at construction.
     *
     * <p>{@link TopicManager} creates an {@code AdminClient} in its
     * constructor, which starts a connection-retry thread immediately. Only
     * {@link EnhancedKafkaListener} methods create topics, so a service using
     * just {@link KafkaListener} would otherwise pay for a client it never
     * touches — and log its connection failures indefinitely.
     *
     * <p>Guarded by {@link #topicManagerLock}; read via
     * {@link #topicManager()}.
     */
    private volatile TopicManager topicManager;

    private final Object topicManagerLock = new Object();
    private final ExecutorService serviceExecutor;
    private final CountDownLatch shutdownLatch;
    private volatile boolean isRunning = false;
    
    public Fluid() {
        this.producer = new AdvancedKafkaProducer();
        this.consumer = new AdvancedKafkaConsumer();
        this.serviceExecutor = Executors.newCachedThreadPool();
        this.shutdownLatch = new CountDownLatch(1);
    }
    
    /**
     * Start the enhanced Fluid framework
     */
    public void start(String[] args) throws Exception {
        logger.info("🌊 Enhanced Fluid Framework Starting 🌀");
        
        // Discover service classes
        List<Object> services = discoverServiceClasses();
        if (services.isEmpty()) {
            logger.warn("⚠️ No service classes found.");
        } else {
            logger.info("✅ Discovered {} service(s):", services.size());
            services.forEach(svc -> logger.info("  🔹 {}", svc.getClass().getSimpleName()));
        }
        
        // Register handlers for both listener annotations
        registerListeners(services);
        
        // Setup graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("⛔ Shutting down Enhanced Fluid Framework...");
            shutdown();
        }));
        
        isRunning = true;
        logger.info("🚀 Enhanced Fluid Framework started successfully!");
        
        // Wait for shutdown signal
        shutdownLatch.await();
    }
    
    /**
     * Registers every annotated handler found on the discovered services.
     *
     * <p>Two listener annotations are supported side by side:
     * <ul>
     *   <li>{@link KafkaListener} — the original three-attribute API, driven
     *       by {@link KafkaProcessor}</li>
     *   <li>{@link EnhancedKafkaListener} — the advanced API adding batching,
     *       partitioning, consumer tuning and dead letter routing</li>
     * </ul>
     *
     * <p>A service may mix the two across different methods. A single method
     * carrying both is rejected: each annotation drives an independent
     * consumer, so the handler would be invoked twice per record.
     *
     * @throws IllegalStateException if any method carries both annotations
     */
    private void registerListeners(List<Object> services) {
        List<String> conflicts = new ArrayList<>();
        List<Method> enhancedMethods = new ArrayList<>();
        int standardCount = 0;

        for (Object service : services) {
            for (Method method : service.getClass().getDeclaredMethods()) {
                boolean enhanced = method.isAnnotationPresent(EnhancedKafkaListener.class);
                boolean standard = method.isAnnotationPresent(KafkaListener.class);

                if (enhanced && standard) {
                    conflicts.add(service.getClass().getSimpleName() + "." + method.getName());
                } else if (enhanced) {
                    enhancedMethods.add(method);
                    processEnhancedListener(service, method);
                } else if (standard) {
                    standardCount++;
                }
            }
        }

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                "Methods annotated with both @KafkaListener and @EnhancedKafkaListener "
                    + "would consume each record twice: " + conflicts);
        }

        // KafkaProcessor performs its own scan for @KafkaListener, so it is
        // handed the services wholesale rather than method by method.
        if (standardCount > 0) {
            KafkaProcessor.processListeners(services.toArray());
        }

        int total = enhancedMethods.size() + standardCount;
        if (total == 0) {
            logger.warn("⚠️ {} service(s) discovered but no @KafkaListener or "
                + "@EnhancedKafkaListener methods found — nothing will be consumed.",
                services.size());
        } else {
            logger.info("✅ Registered {} listener(s): {} standard, {} enhanced",
                total, standardCount, enhancedMethods.size());
        }
    }
    
    private void processEnhancedListener(Object service, Method method) {
        EnhancedKafkaListener listener = method.getAnnotation(EnhancedKafkaListener.class);
        
        serviceExecutor.submit(() -> {
            try {
                // Create topic if it doesn't exist
                if (shouldCreateTopic(listener)) {
                    createTopic(listener);
                }
                
                // Configure consumer based on listener settings
                configureAndStartConsumer(service, method, listener);
                
            } catch (Exception e) {
                logger.error("Error processing enhanced listener: {}", e.getMessage(), e);
            }
        });
    }
    
    private void configureAndStartConsumer(Object service, Method method, EnhancedKafkaListener listener) {
        String topic = listener.topic();
        String groupId = listener.groupId();
        String bootstrapServers = KafkaConfig.resolveBootstrapServers(listener.bootstrapServers());
        
        // Create advanced consumer with custom configuration
        AdvancedKafkaConsumer customConsumer = createCustomConsumer(bootstrapServers, groupId, listener);
        
        if (listener.batchEnabled()) {
            // Batch processing
            customConsumer.consumeBatch(bootstrapServers, groupId, topic, 
                createBatchMessageHandler(service, method, listener));
        } else {
            // Single message processing
            customConsumer.subscribe(bootstrapServers, groupId, Collections.singletonList(topic),
                createMessageHandler(service, method, listener));
        }
    }
    
    private AdvancedKafkaConsumer createCustomConsumer(String bootstrapServers, String groupId, 
                                                      EnhancedKafkaListener listener) {
        AdvancedKafkaConsumer customConsumer = new AdvancedKafkaConsumer();
        
        // Apply custom consumer configuration
        Properties customConfig = new Properties();
        customConfig.putAll(KafkaConfig.createConsumerConfig(groupId));
        customConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        customConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, listener.maxPollRecords());
        customConfig.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, listener.sessionTimeoutMs());
        customConfig.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, listener.heartbeatIntervalMs());
        customConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, String.valueOf(listener.enableAutoCommit()));
        
        // Set partition assignment strategy
        String assignmentStrategy = getPartitionAssignmentStrategy(listener.partitionAssignmentStrategy());
        customConfig.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, assignmentStrategy);
        
        return customConsumer;
    }
    
    private String getPartitionAssignmentStrategy(String strategy) {
        switch (strategy.toLowerCase()) {
            case "roundrobin":
                return "org.apache.kafka.clients.consumer.RoundRobinAssignor";
            case "sticky":
                return "org.apache.kafka.clients.consumer.StickyAssignor";
            case "range":
            default:
                return "org.apache.kafka.clients.consumer.RangeAssignor";
        }
    }
    
    private AdvancedKafkaConsumer.MessageHandler createMessageHandler(Object service, Method method, 
                                                                     EnhancedKafkaListener listener) {
        return record -> {
            try {
                // Extract parameters
                Object[] params = extractMessageParameters(method, record, listener);
                
                // Invoke service method
                Object result = method.invoke(service, params);
                
                // Handle result (e.g., send to dead letter queue or reply topic)
                handleMessageResult(result, listener, record);
                
            } catch (Exception e) {
                logger.error("Error processing message: {}", e.getMessage(), e);
                handleMessageError(e, listener, record);
            }
        };
    }
    
    private AdvancedKafkaConsumer.BatchMessageHandler createBatchMessageHandler(Object service, Method method,
                                                                               EnhancedKafkaListener listener) {
        return records -> {
            logger.debug("Processing batch of {} messages", records.size());
            
            try {
                // Process batch
                for (ConsumerRecord<String, String> record : records) {
                    Object[] params = extractMessageParameters(method, record, listener);
                    method.invoke(service, params);
                }
                
                logger.debug("Successfully processed batch of {} messages", records.size());
                
            } catch (Exception e) {
                logger.error("Error processing batch: {}", e.getMessage(), e);
                
                // Handle batch error (e.g., send to dead letter queue)
                if (listener.enableDeadLetterQueue() && !listener.deadLetterTopic().isEmpty()) {
                    String errorMessage = String.format("Batch processing failed: %s", e.getMessage());
                    producer.sendMessage(listener.deadLetterTopic(), "batch-error", errorMessage);
                }
            }
        };
    }
    
    private Object[] extractMessageParameters(Method method, ConsumerRecord<String, String> record,
                                            EnhancedKafkaListener listener) {
        Class<?>[] paramTypes = method.getParameterTypes();
        Object[] params = new Object[paramTypes.length];
        
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == String.class) {
                if (i == 0) {
                    params[i] = record.value();
                } else if (i == 1) {
                    params[i] = record.key();
                }
            } else if (paramTypes[i] == ConsumerRecord.class) {
                params[i] = record;
            }
        }
        
        return params;
    }
    
    private void handleMessageResult(Object result, EnhancedKafkaListener listener, ConsumerRecord<String, String> record) {
        // Handle successful message processing result
        if (result != null) {
            logger.debug("Message processed successfully, result: {}", result);
            
            // Could implement reply-to functionality here
            // if (listener.replyTo() != null && !listener.replyTo().isEmpty()) {
            //     producer.sendMessage(listener.replyTo(), record.key(), result.toString());
            // }
        }
    }
    
    private void handleMessageError(Exception error, EnhancedKafkaListener listener, ConsumerRecord<String, String> record) {
        // Handle message processing errors
        if (listener.enableDeadLetterQueue() && !listener.deadLetterTopic().isEmpty()) {
            String errorMessage = String.format("Original topic: %s, Partition: %d, Offset: %d, Error: %s",
                record.topic(), record.partition(), record.offset(), error.getMessage());
            producer.sendMessage(listener.deadLetterTopic(), record.key(), errorMessage);
        }
    }
    
    private boolean shouldCreateTopic(EnhancedKafkaListener listener) {
        // For demo purposes, we'll create topics. In production, you might want to check
        // if the topic exists first
        return true;
    }
    
    private void createTopic(EnhancedKafkaListener listener) {
        try {
            NewTopic topic = KafkaConfig.createAdvancedTopic(
                listener.topic(), 
                listener.partitions(), 
                listener.replicationFactor()
            );
            
            topicManager().createTopic(topic);
            logger.info("Created topic '{}' with {} partitions and replication factor {}", 
                       listener.topic(), listener.partitions(), listener.replicationFactor());
                       
        } catch (Exception e) {
            logger.error("Failed to create topic '{}': {}", listener.topic(), e.getMessage(), e);
        }
    }
    
    private List<Object> discoverServiceClasses() throws Exception {
        List<Object> instances = new ArrayList<>();
        File currentDir = new File(".");
        
        File[] serviceFiles = currentDir.listFiles((dir, name) -> name.endsWith("Service.java"));
        if (serviceFiles == null) return instances;
        
        for (File file : serviceFiles) {
            String className = file.getName().replace(".java", "");
            try {
                Class<?> clazz = Class.forName(className);
                if (!java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    instances.add(instance);
                }
            } catch (Throwable t) {
                logger.error("❌ Could not load {}: {}", className, t.getMessage());
            }
        }
        
        return instances;
    }
    
    /**
     * Returns the topic manager, opening it on first call.
     */
    private TopicManager topicManager() {
        TopicManager local = topicManager;
        if (local == null) {
            synchronized (topicManagerLock) {
                local = topicManager;
                if (local == null) {
                    logger.debug("Opening AdminClient for topic management");
                    local = new TopicManager();
                    topicManager = local;
                }
            }
        }
        return local;
    }
    
    /**
     * Get the producer instance for manual use
     */
    public AdvancedKafkaProducer getProducer() {
        return producer;
    }
    
    /**
     * Get the consumer instance for manual use
     */
    public AdvancedKafkaConsumer getConsumer() {
        return consumer;
    }
    
    /**
     * Get framework metrics
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("producerMetrics", producer.getMetrics());
        metrics.put("consumerMetrics", consumer.getMetrics());
        metrics.put("isRunning", isRunning);
        return metrics;
    }
    
    /**
     * Graceful shutdown
     */
    public void shutdown() {
        if (!isRunning) return;
        
        isRunning = false;
        logger.info("Initiating graceful shutdown...");
        
        try {
            producer.shutdown();
            consumer.shutdown();
            KafkaProcessor.shutdown();

            TopicManager openTopicManager = topicManager;
            if (openTopicManager != null) {
                openTopicManager.close();
            }

            serviceExecutor.shutdown();
            
            if (!serviceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                serviceExecutor.shutdownNow();
            }
            
            shutdownLatch.countDown();
            logger.info("✅ Enhanced Fluid Framework shut down successfully");
            
        } catch (Exception e) {
            logger.error("Error during shutdown: {}", e.getMessage(), e);
        }
    }
    
    public static void main(String[] args) throws Exception {
        Fluid fluid = new Fluid();
        fluid.start(args);
    }
}