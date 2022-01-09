package org.vena.bosk;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Some handy defaults for {@link Entity} implementations that don't
 * inherit {@link ReflectiveEntity}.
 */
public abstract class AbstractEntity implements Entity {

	@Override
	public String toString() {
		return getClass().getSimpleName() + "(" + id() + ")";
	}

	@Override
	public int hashCode() {
		throw notSupported("hashCode");
	}

	@Override
	@SuppressFBWarnings(value = "EQ_UNUSUAL", justification = "Yes, it's unusual not to support equals()")
	public boolean equals(Object obj) {
		if (this == obj) {
			// Required to be reflexive by the specification of Object.equals.
			return true;
		} else {
			throw notSupported("equals");
		}
	}

	private IllegalArgumentException notSupported(String methodName) {
		return new IllegalArgumentException(
				getClass().getSimpleName() + "." + methodName
				+ " not supported; see `Entity` javadocs for more information");
	}

}
