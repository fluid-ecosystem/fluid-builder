import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Advanced Topic Manager for Kafka
 * Handles topic creation, configuration, and management
 */
public class TopicManager {
    
    private static final Logger logger = LoggerFactory.getLogger(TopicManager.class);
    private final AdminClient adminClient;
    
    public TopicManager() {
        Properties config = new Properties();
        config.put("bootstrap.servers", KafkaConfig.DEFAULT_BOOTSTRAP_SERVERS);
        this.adminClient = AdminClient.create(config);
    }
    
    /**
     * Create a single topic
     */
    public void createTopic(NewTopic topic) throws ExecutionException, InterruptedException {
        CreateTopicsResult result = adminClient.createTopics(Collections.singleton(topic));
        result.all().get();
        logger.info("Successfully created topic: {}", topic.name());
    }
    
    /**
     * Create multiple topics
     */
    public void createTopics(Collection<NewTopic> topics) throws ExecutionException, InterruptedException {
        CreateTopicsResult result = adminClient.createTopics(topics);
        result.all().get();
        logger.info("Successfully created {} topics", topics.size());
    }
    
    /**
     * Check if topic exists
     */
    public boolean topicExists(String topicName) throws ExecutionException, InterruptedException {
        ListTopicsResult result = adminClient.listTopics();
        Set<String> topics = result.names().get();
        return topics.contains(topicName);
    }
    
    /**
     * List all topics
     */
    public Set<String> listTopics() throws ExecutionException, InterruptedException {
        ListTopicsResult result = adminClient.listTopics();
        return result.names().get();
    }
    
    /**
     * Delete a topic
     */
    public void deleteTopic(String topicName) throws ExecutionException, InterruptedException {
        adminClient.deleteTopics(Collections.singleton(topicName)).all().get();
        logger.info("Successfully deleted topic: {}", topicName);
    }
    
    /**
     * Get topic configuration
     */
    public Map<String, String> getTopicConfig(String topicName) throws ExecutionException, InterruptedException {
        return adminClient.describeTopics(Collections.singleton(topicName))
            .all()
            .get()
            .get(topicName)
            .configs()
            .entrySet()
            .stream()
            .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
    }
    
    /**
     * Update topic configuration
     */
    public void updateTopicConfig(String topicName, Map<String, String> configs) throws ExecutionException, InterruptedException {
        org.apache.kafka.clients.admin.AlterConfigsResult result = adminClient.alterConfigs(
            Collections.singletonMap(new org.apache.kafka.common.config.TopicConfigResource(
                org.apache.kafka.common.config.TopicConfigResource.Type.TOPIC, topicName), configs)
        );
        result.all().get();
        logger.info("Successfully updated configuration for topic: {}", topicName);
    }
    
    /**
     * Create topics from a list of topic specifications
     */
    public void createTopicsFromSpecs(List<TopicSpec> specs) throws ExecutionException, InterruptedException {
        List<NewTopic> topics = specs.stream()
            .map(spec -> {
                NewTopic topic = new NewTopic(spec.getName(), spec.getPartitions(), spec.getReplicationFactor());
                if (spec.getConfigs() != null && !spec.getConfigs().isEmpty()) {
                    topic.configs(spec.getConfigs());
                }
                return topic;
            })
            .toList();
        
        createTopics(topics);
    }
    
    /**
     * Close the admin client
     */
    public void close() {
        adminClient.close();
    }
    
    /**
     * Topic specification class
     */
    public static class TopicSpec {
        private final String name;
        private final int partitions;
        private final short replicationFactor;
        private final Map<String, String> configs;
        
        public TopicSpec(String name, int partitions, short replicationFactor) {
            this(name, partitions, replicationFactor, null);
        }
        
        public TopicSpec(String name, int partitions, short replicationFactor, Map<String, String> configs) {
            this.name = name;
            this.partitions = partitions;
            this.replicationFactor = replicationFactor;
            this.configs = configs != null ? configs : new HashMap<>();
        }
        
        public String getName() { return name; }
        public int getPartitions() { return partitions; }
        public short getReplicationFactor() { return replicationFactor; }
        public Map<String, String> getConfigs() { return configs; }
        
        public static TopicSpec basic(String name) {
            return new TopicSpec(name, KafkaConfig.DEFAULT_PARTITIONS, KafkaConfig.DEFAULT_REPLICATION_FACTOR);
        }
        
        public static TopicSpec advanced(String name, int partitions, short replicationFactor, Map<String, String> configs) {
            return new TopicSpec(name, partitions, replicationFactor, configs);
        }
    }
}