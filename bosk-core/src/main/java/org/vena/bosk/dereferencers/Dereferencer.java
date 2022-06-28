package org.vena.bosk.dereferencers;

import org.vena.bosk.Bosk.NonexistentEntryException;
import org.vena.bosk.Reference;

/**
 * Internal interface for operations you can do on a {@link Reference} to access
 * and modify the thing it points at. The "compiled form" of a Reference's Path.
 *
 * <p>
 * Ideally we'd use generics here for clarity and type safety, but that makes
 * bytecode generation more cumbersome. Maybe one day. This is not a user-visible
 * interface, so type safety is less of a concern; only the Bosk internals need
 * to be careful with it.
 *
 * <p>
 * Because compiled code is loaded by a different {@link ClassLoader}, it will
 * appear to be in a different "runtime package" and therefore can't access
 * package-private classes; hence, we must make this public.
 */
public interface Dereferencer {
	/**
	 * @param source the bosk root object
	 * @param ref points to the object to get
	 * @return the object pointed to by <code>ref</code>
	 * @throws NonexistentEntryException if any segment of
	 * <code>ref</code> refers to an object that does not exist
	 */
	Object get(Object source, Reference<?> ref) throws NonexistentEntryException;

	/**
	 * @param source the bosk root object
	 * @param ref points to some object (which may or may not exist) in the container that is to contain
	 *            <code>newObject</code>; the {@link org.vena.bosk.Path#lastSegment last segment} of
	 *            the reference's path is ignored
	 * @param newValue the object to put in the container
	 * @return a new version of the bosk root object, equivalent to <code>source</code> in every respect
	 * except that the object containing <code>ref</code> now contains <code>newValue</code>
	 * @throws NonexistentEntryException if any segment of
	 * <code>ref</code> refers to an object that does not exist, except the last segment,
	 * which is ignored.
	 */
	Object with(Object source, Reference<?> ref, Object newValue) throws NonexistentEntryException;

	/**
	 * @param source the bosk root object
	 * @param ref points to the object to remove
	 * @return a new version of the bosk root object, equivalent to <code>source</code> in every respect
	 * except that the object at <code>ref</code> is gone.
	 * @throws IllegalArgumentException if <code>ref</code> points at something that cannot be deleted,
	 * like a non-{@link java.util.Optional} object field, as opposed to something like a Catalog entry
	 * @throws NonexistentEntryException if any segment of
	 * <code>ref</code> refers to an object that does not exist
	 */
	Object without(Object source, Reference<?> ref) throws NonexistentEntryException;

}
