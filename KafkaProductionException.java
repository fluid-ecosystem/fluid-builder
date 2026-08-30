
/**
 * Custom exception for Kafka production operations
 */
public class KafkaProductionException extends Exception {
    
    public KafkaProductionException(String message) {
        super(message);
    }
    
    public KafkaProductionException(String message, Throwable cause) {
        super(message, cause);
    }
}