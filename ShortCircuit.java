import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ShortCircuit {
    String topic();
    String bootstrapServers() default "kafka:9092";
}
