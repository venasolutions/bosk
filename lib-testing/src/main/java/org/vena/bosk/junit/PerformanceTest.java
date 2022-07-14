package org.vena.bosk.junit;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ ANNOTATION_TYPE, METHOD, CONSTRUCTOR })
@Retention(RUNTIME)
@Test
@EnabledIfEnvironmentVariable(named = "RUN_PERFORMANCE_TESTS", matches = ".*")
public @interface PerformanceTest {
}
