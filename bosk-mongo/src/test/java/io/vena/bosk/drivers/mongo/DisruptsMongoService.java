package io.vena.bosk.drivers.mongo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ_WRITE;

/**
 * Indicates that a test is going to use {@link MongoService},
 * and that it will use {@link MongoService#proxy()} to test
 * network outages and other errors.
 * Use this annotation instead of {@link UsesMongoService} to ensure
 * this test won't be run in parallel with other tests that
 * it might disrupt.
 * Only one {@link DisruptsMongoService} will run at a time,
 * and never at the same time as any {@link UsesMongoService}.
 *
 * <p>
 * Each test class should use a distinct database name so that
 * classes can run in parallel.
 *
 * <p>
 * When in doubt, it's always safe to use this,
 * but doing so unnecessarily will impede parallelism.
 */
@Target({ ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(value="mongoContainer", mode=READ_WRITE)
public @interface DisruptsMongoService {
}
