/**
 * Advanced partition strategy annotation
 * Allows custom partitioning logic for message distribution
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PartitionStrategy {
    enum Strategy {
        KEY_BASED,      // Partition based on message key
        ROUND_ROBIN,    // Round-robin distribution
        STICKY,         // Sticky partition assignment
        CUSTOM          // Custom partitioning logic
    }
    
    Strategy value() default Strategy.KEY_BASED;
    String customPartitionerClass() default "";
    int customPartitionCount() default 3;
}