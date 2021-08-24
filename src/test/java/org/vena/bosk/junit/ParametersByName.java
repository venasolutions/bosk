package org.vena.bosk.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

@Target({ ElementType.ANNOTATION_TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ParametersByNameContextProvider.class)
@TestTemplate
public @interface ParametersByName {
	String name() default "";
	int singleInvocationIndex() default 0; // 0 runs all tests; indexes count from 1
}
