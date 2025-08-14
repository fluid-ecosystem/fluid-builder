import org.apache.kafka.clients.consumer.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class KafkaProcessor {
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public static void processListeners(Object... beans) {
        for (Object bean : beans) {
            processBean(bean);
        }
    }

    private static void processBean(Object bean) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            // System.out.println("🔍 Processing method: " + bean.getClass() + "/" + method.getName());
            if (method.isAnnotationPresent(KafkaListener.class)) {
                startListener(bean, method);
            }
        }
    }

    private static void startListener(Object bean, Method method) {
        executor.submit(() -> {
            try {
                KafkaListener config = method.getAnnotation(KafkaListener.class);
                Properties props = new Properties();
                props.put("bootstrap.servers", config.bootstrapServers());
                props.put("group.id", config.groupId());
                props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
                props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
                props.put("auto.offset.reset", "earliest");

                Consumer<String, String> consumer = new KafkaConsumer<>(props);
                consumer.subscribe(Collections.singleton(config.topic()));

                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        invokeListener(bean, method, record);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private static void invokeListener(Object bean, Method method, ConsumerRecord<String, String> record) {
        try {
            Object result = null;
            if (method.getParameterCount() == 1) {
                result = method.invoke(bean, record.value());
            } else if (method.getParameterCount() == 2) {
                result = method.invoke(bean, record.key(), record.value());
            }
            // If @SendTo is present, send the result to the specified topic
            if (method.isAnnotationPresent(SendTo.class) && result != null) {
                SendTo sendTo = method.getAnnotation(SendTo.class);
                String topic = sendTo.topic();
                String bootstrapServers = sendTo.bootstrapServers();
                KafkaMessenger.sendMessage(bootstrapServers, topic, null, result.toString());
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    public static void shutdown() {
        executor.shutdown();
        KafkaMessenger.shutdown();
    }
}
