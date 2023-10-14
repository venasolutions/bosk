package io.vena.bosk.annotations;

import io.vena.bosk.Bosk;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a method to be registered as a hook
 * for an object passed to {@link Bosk#registerHooks}.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface Hook {
	/**
	 * The scope of the hook for this method.
	 */
	String value();
}
