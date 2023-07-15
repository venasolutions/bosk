package io.vena.bosk.dereferencers;

import io.vena.bosk.Path;
import java.lang.reflect.Type;

/**
 * Provides info for a particular {@link Path}, determined by reflection analysis.
 * Call {@link #buildInstance()} do perform the compilation and class loading.
 * <p>
 *
 * Dereferencer construction is separated into two phases:
 * first we create a {@link DereferencerBuilder},
 * and then from that we compile the {@link Dereferencer}.
 * This allows us to do just the reflection analysis, and skip the compilation and class loading,
 * in cases where we don't need the latter.
 */
interface DereferencerBuilder {
	Type targetType();
	Path fullyParameterizedPath();

	/**
	 * Compiles and loads a new {@link Dereferencer} subclass.
	 * @return an instance of the new {@link Dereferencer}
	 */
	Dereferencer buildInstance();
}
