package org.vena.bosk.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.vena.bosk.Bosk.ReadContext;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;
import org.vena.bosk.ReflectiveEntity;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Marks a type as being a short-lived value, computed from bosk structures,
 * whose {@link Entity} fields do not denote a containment operation as they
 * usually do in a bosk structure.
 * This allows you to create data structures referencing bosk objects without
 * the hassle of pedantically using explicit {@link Reference}s everywhere.
 *
 * <p>
 * "Short-lived" here refers to the fact that a {@link DerivedRecord} is derived
 * from a snapshot of the state of the bosk because it contains objects rather
 * than {@link Reference}s. It will not evolve along with the bosk, so it's
 * suitable, for example, during a single HTTP request; but if retained longer,
 * it runs the risk of holding onto old state that may be incorrect, and may
 * increase the application's memory footprint by retaining multiple
 * "generations" of the same objects.
 *
 * <p>
 * During JSON serialization, any {@link ReflectiveEntity} fields are serialized
 * and deserialized as though they were {@link Reference}s, which is probably
 * what you want. This means that deserialization must be done inside a {@link
 * ReadContext}.
 *
 * <p>
 * A {@link DerivedRecord} object is not valid in a bosk. If serialized or
 * deserialized, it is not permitted to have any {@link Entity} fields unless
 * they are also instances of {@link ReflectiveEntity}.
 *
 * @author Patrick Doyle
 *
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface DerivedRecord {

}
