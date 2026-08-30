import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ShortCircuit {
    String topic();
    /**
     * Broker the error is published to.
     *
     * <p>Blank means inherit {@link KafkaConfig#defaultBootstrapServers()}.
     */
    String bootstrapServers() default "";
}
