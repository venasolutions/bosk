package org.vena.bosk;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Abstract base class for {@link ConfigurationNode}s with an {@link Identifier}
 * that can be used in such structures as {@link Catalog}s and {@link Listing}s.
 *
 * <p>
 * <b>Note</b>: In general, an {@link Entity} does not have enough information
 * to determine its "identity", in the sense that it's impossible to tell whether
 * two Java objects represent the same underlying entity. <b>{@link #id()} is not
 * globally unique.</b>
 *
 * <p>
 * For that reason, {@link #hashCode()} and {@link #equals(Object)} throw {@link
 * IllegalArgumentException}. You can override these if you know what you are
 * doing, but it is not recommended outside of test cases.
 *
 * <p>
 * If you think you want to create a <code>Set</code> of your entity objects, or
 * use them as <code>Map</code> keys, consider using {@link Reference}s as
 * keys instead. In the Bosk system, {@link Reference}s are a reliable way to
 * indicate the identity of an object, because an object's identity is defined
 * by its location in the document tree. (There is no notion of "moving" an
 * object in a Bosk while retaining its identity.)
 *
 * @author pdoyle
 */
public abstract class Entity implements ConfigurationNode {
	/**
	 * @return an {@link Identifier} that uniquely identifies this {@link
	 * Entity} within its containing {@link Catalog}.  Note that this is not
	 * guaranteed unique outside the scope of the {@link Catalog}.
	 */
	public abstract Identifier id();

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
