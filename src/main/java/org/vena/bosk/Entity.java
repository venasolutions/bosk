package org.vena.bosk;

/**
 * A {@link StateTreeNode} representing a thing with its own {@link #id() identity}
 * (as opposed to a mere value) that can reside in a {@link Catalog} and be referenced
 * by {@link Listing} and {@link SideTable}.
 *
 * <p>
 * <b>Note</b>: In general, an {@link Entity} does not have enough information
 * to determine its "identity", in the sense that it's impossible to tell whether
 * two Java objects represent the same underlying entity. <b>{@link #id} is not
 * globally unique.</b> This means you must take special care if you want to use
 * an {@link Entity} in a context where {@link #hashCode} and {@link #equals}
 * matter: You very likely <em>do not</em> want these methods to check only
 * the {@link #id}.
 *
 * <p>
 * If you think you want to create a <code>Set</code> of your entity objects, or
 * use them as <code>Map</code> keys, consider using {@link Reference}s as
 * keys instead. In the Bosk system, {@link Reference}s are a reliable way to
 * indicate the identity of an object, because an object's identity is defined
 * by its location in the document tree. (There is no notion of "moving" an
 * object in a Bosk while retaining its identity.)
 *
 * <p>
 * If the entity must be aware of its own identity, consider {@link ReflectiveEntity}.
 *
 * @see Catalog
 * @see ReflectiveEntity
 * @author pdoyle
 */
public interface Entity extends StateTreeNode {
	/**
	 * @return an {@link Identifier} that uniquely identifies this {@link
	 * Entity} within its containing {@link Catalog}.  Note that this is not
	 * guaranteed unique outside the scope of the {@link Catalog}.
	 */
	Identifier id();
}
