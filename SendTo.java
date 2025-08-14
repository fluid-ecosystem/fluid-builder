import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SendTo {
    String topic();
    String bootstrapServers() default "kafka-cluster:9092";
}
