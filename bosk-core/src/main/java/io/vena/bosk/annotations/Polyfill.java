package io.vena.bosk.annotations;

import io.vena.bosk.StateTreeNode;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a static final field in a {@link StateTreeNode} to indicate that it can be
 * used as a default value for a given field, for backward compatibility with external
 * systems that don't yet support the field.
 *
 * <p>
 * This is not meant to be used just to supply default values for optional fields;
 * that should be achieved by declaring the field {@link Optional}
 * and calling {@link Optional#orElse} when the field is used.
 * Rather, this is meant to be used <em>temporarily</em> with newly added fields
 * to support systems that are not yet aware of those fields.
 *
 * @author Patrick Doyle
 */
@Retention(RUNTIME)
@Target({ FIELD })
public @interface Polyfill {
	/**
	 * The names of the fields for which we're supplying a default value.
	 */
	String[] value();
}
