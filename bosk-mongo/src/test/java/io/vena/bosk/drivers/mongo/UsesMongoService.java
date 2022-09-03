package io.vena.bosk.drivers.mongo;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.parallel.ResourceLock;

import static org.junit.jupiter.api.parallel.ResourceAccessMode.READ;

/**
 * Indicates that a test is going to use {@link MongoService}.
 * Use this annotation to prevent a test from running
 * in parallel with {@link DisruptsMongoService}s,
 * which would otherwise cause spurious errors.
 *
 * <p>
 * Each test class should use a distinct database name so that
 * classes can run in parallel on the same MongoDB container.
 *
 * <p>
 * Tests that need to use {@link MongoService#proxy()} to test
 * network outages and other kinds of errors should instead
 * use {@link DisruptsMongoService} to make sure they don't run in
 * parallel with other tests and interfere with them.
 */
@Target({ ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@ResourceLock(value="mongoContainer", mode= READ)
public @interface UsesMongoService {
}
