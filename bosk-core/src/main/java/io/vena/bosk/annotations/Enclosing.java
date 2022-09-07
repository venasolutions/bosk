package io.vena.bosk.annotations;

import io.vena.bosk.Entity;
import io.vena.bosk.Reference;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a {@link Reference} parameter in an {@link Entity} constructor to indicate that the
 * reference should point to an enclosing entity of the entity itself, as defined by
 * {@link Reference#enclosingReference(Class)}.
 *
 * <p>
 * Enclosing references are not serialized, and are created automatically during deserialization.
 *
 * @author Patrick Doyle
 */
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER })
public @interface Enclosing {

}
