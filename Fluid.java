import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Fluid framework entry point.
 * 
 * Features:
 * - Automatic topic creation with custom configurations
 * - Producer and consumer management
 * - Batch processing capabilities
 * - Dead letter queue support
 * - Performance monitoring
 * - Graceful shutdown handling
 * - Multiple consumer groups
 * - Custom partitioning strategies
 */
public class Fluid {
    
    private static final Logger logger = LoggerFactory.getLogger(Fluid.class);
    
    private final MessageProducer producer;
    private final MessageConsumer consumer;
    /**
     * Opened on first use rather than at construction.
     *
     * <p>{@link TopicManager} creates an {@code AdminClient} in its
     * constructor, which starts a connection-retry thread immediately. Only
     * {@link KafkaSubscription} methods create topics, so a service using
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
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    public Fluid() {
        this.producer = new MessageProducer();
        this.consumer = new MessageConsumer();
        this.serviceExecutor = Executors.newCachedThreadPool();
        this.shutdownLatch = new CountDownLatch(1);
    }
    
    /**
     * Start the framework
     */
    public void start(String[] args) throws Exception {
        logger.info("🌊 Fluid Framework Starting 🌀");
        
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
            logger.info("⛔ Shutting down Fluid Framework...");
            shutdown();
        }));
        
        isRunning = true;
        logger.info("🚀 Fluid Framework started successfully!");
        
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
     *   <li>{@link KafkaSubscription} — the fuller API adding batching,
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
        List<Method> subscriptionMethods = new ArrayList<>();
        int standardCount = 0;

        for (Object service : services) {
            for (Method method : service.getClass().getDeclaredMethods()) {
                boolean subscription = method.isAnnotationPresent(KafkaSubscription.class);
                boolean standard = method.isAnnotationPresent(KafkaListener.class);

                if (subscription && standard) {
                    conflicts.add(service.getClass().getSimpleName() + "." + method.getName());
                } else if (subscription) {
                    subscriptionMethods.add(method);
                    processSubscription(service, method);
                } else if (standard) {
                    standardCount++;
                }
            }
        }

        if (!conflicts.isEmpty()) {
            throw new IllegalStateException(
                "Methods annotated with both @KafkaListener and @KafkaSubscription "
                    + "would consume each record twice: " + conflicts);
        }

        // KafkaProcessor performs its own scan for @KafkaListener, so it is
        // handed the services wholesale rather than method by method.
        if (standardCount > 0) {
            KafkaProcessor.processListeners(services.toArray());
        }

        int total = subscriptionMethods.size() + standardCount;
        if (total == 0) {
            logger.warn("⚠️ {} service(s) discovered but no @KafkaListener or "
                + "@KafkaSubscription methods found — nothing will be consumed.",
                services.size());
        } else {
            logger.info("✅ Registered {} listener(s): {} via @KafkaListener, {} via @KafkaSubscription",
                total, standardCount, subscriptionMethods.size());
        }
    }
    
    private void processSubscription(Object service, Method method) {
        KafkaSubscription listener = method.getAnnotation(KafkaSubscription.class);
        
        serviceExecutor.submit(() -> {
            try {
                // Create topic if it doesn't exist
                if (shouldCreateTopic(listener)) {
                    createTopic(listener);
                }
                
                // Configure consumer based on listener settings
                configureAndStartConsumer(service, method, listener);
                
            } catch (Exception e) {
                logger.error("Error processing subscription: {}", e.getMessage(), e);
            }
        });
    }
    
    private void configureAndStartConsumer(Object service, Method method, KafkaSubscription listener) {
        String topic = listener.topic();
        String groupId = listener.groupId();
        String bootstrapServers = KafkaConfig.resolveBootstrapServers(listener.bootstrapServers());
        
        Properties overrides = buildConsumerOverrides(listener);
        
        if (listener.batchEnabled()) {
            // Batch processing
            consumer.consumeBatch(bootstrapServers, groupId, topic, 
                createBatchMessageHandler(service, method, listener), overrides);
        } else {
            // Single message processing
            consumer.subscribe(bootstrapServers, groupId, Collections.singletonList(topic),
                createMessageHandler(service, method, listener), overrides);
        }
    }
    
    /**
     * Translates a listener's tuning attributes into consumer properties.
     *
     * <p>These were previously assembled into a local {@code Properties} that
     * was never passed anywhere, so every tuning attribute on
     * {@link KafkaSubscription} was silently inert.
     *
     * <p>Values are supplied as strings and parsed by Kafka's own
     * {@code ConfigDef}, which avoids the boxed-type mismatches that
     * {@code Properties} would otherwise carry into the client.
     */
    private Properties buildConsumerOverrides(KafkaSubscription listener) {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,
            String.valueOf(listener.maxPollRecords()));
        overrides.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
            String.valueOf(listener.sessionTimeoutMs()));
        overrides.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
            String.valueOf(listener.heartbeatIntervalMs()));
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
            String.valueOf(listener.enableAutoCommit()));
        overrides.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
            getPartitionAssignmentStrategy(listener.partitionAssignmentStrategy()));
        return overrides;
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
    
    private MessageConsumer.MessageHandler createMessageHandler(Object service, Method method, 
                                                                     KafkaSubscription listener) {
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
    
    private MessageConsumer.BatchMessageHandler createBatchMessageHandler(Object service, Method method,
                                                                               KafkaSubscription listener) {
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
                                            KafkaSubscription listener) {
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
    
    private void handleMessageResult(Object result, KafkaSubscription listener, ConsumerRecord<String, String> record) {
        // Handle successful message processing result
        if (result != null) {
            logger.debug("Message processed successfully, result: {}", result);
            
            // Could implement reply-to functionality here
            // if (listener.replyTo() != null && !listener.replyTo().isEmpty()) {
            //     producer.sendMessage(listener.replyTo(), record.key(), result.toString());
            // }
        }
    }
    
    private void handleMessageError(Exception error, KafkaSubscription listener, ConsumerRecord<String, String> record) {
        // Handle message processing errors
        if (listener.enableDeadLetterQueue() && !listener.deadLetterTopic().isEmpty()) {
            String errorMessage = String.format("Original topic: %s, Partition: %d, Offset: %d, Error: %s",
                record.topic(), record.partition(), record.offset(), error.getMessage());
            producer.sendMessage(listener.deadLetterTopic(), record.key(), errorMessage);
        }
    }
    
    /**
     * Whether the listener's topic needs creating.
     *
     * <p>Consults the broker rather than assuming. Returning a hardcoded
     * {@code true} meant every listener issued a create on every start, which
     * failed with {@code TopicExistsException} for anything already present
     * and logged an error per listener per restart — training readers to
     * ignore the log.
     *
     * <p>A lookup failure returns {@code true} so startup still attempts
     * creation; {@link #createTopic} tolerates the topic already existing.
     */
    private boolean shouldCreateTopic(KafkaSubscription listener) {
        try {
            if (topicManager().topicExists(listener.topic())) {
                logger.debug("Topic '{}' already exists; skipping creation", listener.topic());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.warn("Could not determine whether topic '{}' exists, attempting creation: {}",
                listener.topic(), e.getMessage());
            return true;
        }
    }
    
    private void createTopic(KafkaSubscription listener) {
        try {
            NewTopic topic = KafkaConfig.createConfiguredTopic(
                listener.topic(), 
                listener.partitions(), 
                listener.replicationFactor()
            );
            
            topicManager().createTopic(topic);
            logger.info("Created topic '{}' with {} partitions and replication factor {}", 
                       listener.topic(), listener.partitions(), listener.replicationFactor());
                       
        } catch (Exception e) {
            // A concurrent creator winning the race is expected, not an error:
            // several instances of a service start together and all attempt
            // the same topic.
            if (isTopicExists(e)) {
                logger.debug("Topic '{}' was created concurrently", listener.topic());
            } else {
                logger.error("Failed to create topic '{}': {}", listener.topic(), e.getMessage(), e);
            }
        }
    }

    private static boolean isTopicExists(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TopicExistsException) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Instantiates every class in the working directory that declares a
     * listener.
     *
     * <p>Selection is by annotation, not by file name. The previous
     * {@code *Service.java} filter silently ignored a listener declared
     * anywhere else — a handler in {@code OrderHandler.java} was never found,
     * and the framework reported success having registered nothing.
     *
     * <p>Classes are loaded without initialising them, so the framework's own
     * sources — which sit in the same directory in the container image — are
     * inspected and skipped without running their static initialisers.
     *
     * @throws IllegalStateException if a class declaring listeners cannot be
     *         instantiated; its handlers would never run, and failing here is
     *         clearer than starting a framework that consumes nothing
     */
    private List<Object> discoverServiceClasses() throws Exception {
        File[] sources = new File(".").listFiles((dir, name) -> name.endsWith(".java"));
        if (sources == null) {
            return List.of();
        }

        // Stable order, so startup logs are comparable between runs.
        Arrays.sort(sources, Comparator.comparing(File::getName));

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Object> instances = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (File file : sources) {
            String name = file.getName();
            String className = name.substring(0, name.length() - ".java".length());

            Class<?> clazz;
            try {
                // false: inspect annotations without triggering static
                // initialisers on classes that turn out not to be services.
                clazz = Class.forName(className, false, loader);
            } catch (Throwable t) {
                logger.debug("Skipping {}: not loadable ({})", className, t.toString());
                continue;
            }

            if (!declaresListener(clazz) || !isInstantiable(clazz)) {
                continue;
            }

            try {
                instances.add(clazz.getDeclaredConstructor().newInstance());
            } catch (Throwable t) {
                Throwable cause = rootCause(t);
                logger.error("❌ {} declares listeners but could not be created: {}: {}",
                    className, cause.getClass().getName(), cause.getMessage(), cause);
                failures.add(className + " (" + cause + ")");
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                "Classes declaring listeners could not be instantiated, so their "
                    + "handlers would never run: " + failures);
        }

        return instances;
    }

    /** Whether any declared method carries a listener annotation. */
    private static boolean declaresListener(Class<?> clazz) {
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isAnnotationPresent(KafkaListener.class)
                    || method.isAnnotationPresent(KafkaSubscription.class)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // Unresolvable references in an unrelated class are not our problem.
            logger.debug("Could not inspect {}: {}", clazz.getName(), t.toString());
        }
        return false;
    }

    private static boolean isInstantiable(Class<?> clazz) {
        int modifiers = clazz.getModifiers();
        boolean usable = !Modifier.isAbstract(modifiers)
            && !clazz.isInterface()
            && !clazz.isEnum()
            && !clazz.isAnnotation()
            && (clazz.getEnclosingClass() == null || Modifier.isStatic(modifiers));

        if (!usable) {
            logger.warn("⚠️ {} declares listeners but cannot be instantiated; skipping.",
                clazz.getSimpleName());
        }
        return usable;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
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
    public MessageProducer getProducer() {
        return producer;
    }
    
    /**
     * Get the consumer instance for manual use
     */
    public MessageConsumer getConsumer() {
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
        // Once-only. The previous `if (!isRunning) return` guard raced: two
        // callers could both observe true and run the body twice, while a
        // failure before `isRunning = true` made the JVM hook a no-op and
        // left the latch uncounted.
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        isRunning = false;
        logger.info("Initiating graceful shutdown...");

        try {
            // Each step is isolated. Previously one throwing component
            // skipped every remaining step and, because the countdown sat
            // at the end of the same try, left `start()` blocked on the
            // latch forever.
            closeQuietly("producer", producer::shutdown);
            closeQuietly("consumer", consumer::shutdown);
            closeQuietly("standard listeners", KafkaProcessor::shutdown);

            TopicManager openTopicManager = topicManager;
            if (openTopicManager != null) {
                closeQuietly("topic manager", openTopicManager::close);
            }

            closeQuietly("service executor", () -> {
                serviceExecutor.shutdown();
                if (!serviceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("Service executor did not stop within 30s; forcing shutdown");
                    serviceExecutor.shutdownNow();
                }
            });

            logger.info("✅ Fluid Framework shut down successfully");
        } finally {
            // Unconditional: `start()` is parked on this latch, so failing to
            // count it down hangs the process with no diagnostic.
            shutdownLatch.countDown();
        }
    }

    /**
     * Runs one shutdown step, logging rather than propagating failure, so a
     * single misbehaving component cannot strand the rest.
     */
    private void closeQuietly(String what, ShutdownStep step) {
        try {
            step.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while shutting down {}", what);
        } catch (Exception e) {
            logger.error("Error shutting down {}: {}", what, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ShutdownStep {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Fluid fluid = new Fluid();
        fluid.start(args);
    }
}