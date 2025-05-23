import org.apache.kafka.clients.producer.*;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class KafkaMessenger {
    private static final Map<String, Producer<String, String>> producers = new ConcurrentHashMap<>();
    
    public static void sendMessage(String topic, String message) {
        // sendMessage("localhost:9092", topic, null, message);
        sendMessage("kafka-cluster:9092", topic, null, message);
    }

    public static void sendMessage(String bootstrapServers, String topic, String key, String message) {
        Producer<String, String> producer = producers.computeIfAbsent(bootstrapServers, servers -> {
            Properties props = new Properties();
            props.put("bootstrap.servers", servers);
            props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            return new KafkaProducer<>(props);
        });
        
        producer.send(new ProducerRecord<>(topic, key, message), (metadata, e) -> {
            if (e != null) {
                System.err.println("Error sending message: " + e.getMessage());
            }
        });
    }

    public static void shutdown() {
        producers.values().forEach(Producer::close);
    }
}
