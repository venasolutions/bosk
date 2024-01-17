package io.vena.bosk;

/**
 * Some handy defaults for {@link Entity} implementations that don't
 * inherit {@link ReflectiveEntity}.
 *
 * @deprecated This dates back to a time when value-based equals and hashCode
 * were discouraged, but this is no longer the case. There's no reason newly
 * written Entities should inherit this.
 */
@Deprecated
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
