import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KafkaListener {
    String topic();
    String groupId();
    // String bootstrapServers() default "localhost:9092";
    String bootstrapServers() default "kafka-cluster:9092";
}
