
/**
 * Custom exception for Kafka production operations
 */
public class KafkaProductionException extends Exception {

    private static final long serialVersionUID = 1L;

    public KafkaProductionException(String message) {
        super(message);
    }
    
    public KafkaProductionException(String message, Throwable cause) {
        super(message, cause);
    }
}