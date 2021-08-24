package org.vena.bosk.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a {@link Reference} parameter in an {@link Entity} constructor to indicate that the
 * reference should point to the entity itself.
 *
 * <p>
 * Self-references are not serialized, and are created automatically during deserialization.
 *
 * @author Patrick Doyle
 */
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER })
public @interface Self {

}
