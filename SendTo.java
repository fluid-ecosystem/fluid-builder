import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SendTo {
    String topic();
    /**
     * Broker the result is published to.
     *
     * <p>Blank means inherit {@link KafkaConfig#defaultBootstrapServers()}.
     */
    String bootstrapServers() default "";
}
