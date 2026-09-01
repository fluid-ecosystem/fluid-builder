import org.apache.kafka.clients.producer.*;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class KafkaMessenger {
    private static final Map<String, Producer<String, String>> producers = new ConcurrentHashMap<>();
    
    public static void sendMessage(String topic, String message) {
        sendMessage(KafkaConfig.defaultBootstrapServers(), topic, null, message);
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
                return;
            }
            FrameworkMetrics metrics = FluidMetrics.framework();
            metrics.messageProduced(topic);
            // Recorded as discovered rather than declared: this call site is
            // only known statically when the topic is a literal, and the
            // scanner cannot tell which send is executing here.
            // Identify the producer by the service, not by the class doing the
            // sending. Every service sends through KafkaMessenger, so using the
            // class name collapses every producer in the system into one node.
            metrics.routeTaken(Route.discovered(
                FluidMetrics.configuredJob(), topic, Route.Kind.PRODUCES));
        });
    }

    public static void shutdown() {
        producers.values().forEach(Producer::close);
    }
}
