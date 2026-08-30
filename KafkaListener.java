import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface KafkaListener {
    String topic();
    String groupId();
    /**
     * Broker address for this listener.
     *
     * <p>Blank means inherit {@link KafkaConfig#defaultBootstrapServers()},
     * which reads {@code BOOTSTRAP_SERVERS} from the environment. Set this
     * only to pin one listener to a different broker.
     */
    String bootstrapServers() default "";
}
