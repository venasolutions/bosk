package io.vena.bosk;

/**
 * An entity that can return a {@link #reference()} to itself.
 *
 * <p>
 * Because the bosk system identifies an object by its location in the document
 * tree, this means instances of this class have enough information to determine
 * their identity, and so we provide {@link #equals(Object) equals} and {@link
 * #hashCode() hashCode} implementations.
 *
 * <p>
 * <em>Performance note</em>: References aren't cheap to create.
 *
 * @author Patrick Doyle
 */
public abstract class ReflectiveEntity<T extends ReflectiveEntity<T>> implements Entity {
	public abstract Reference<T> reference();

	@Override
	public int hashCode() {
		return reference().hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		} else if (obj instanceof ReflectiveEntity) {
			return this.reference().equals(((ReflectiveEntity<?>) obj).reference());
		} else {
			return false;
		}
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + "(" + reference() + ")";
	}
}
