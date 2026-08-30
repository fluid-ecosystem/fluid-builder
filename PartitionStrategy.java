import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how a service distributes messages across partitions.
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