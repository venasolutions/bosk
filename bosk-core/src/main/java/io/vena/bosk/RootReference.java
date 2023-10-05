package io.vena.bosk;

import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;

/**
 * A {@link Reference} to the root node of a particular bosk's state tree,
 * doubling as the main way to acquire/construct other references.
 */
public interface RootReference<R> extends Reference<R> {
	/**
	 * Dynamically generates an object that can return {@link Reference}s
	 * as specified by methods annotated with {@link ReferencePath}.
	 *
	 * <p>
	 * This is the recommended way to create references whose paths are known at development time,
	 * which should be most references in application code.
	 * The rest of the reference-creation methods are useful for dynamic situations
	 * where paths are computed at runtime,
	 * which mostly occurs in reusable, highly generalized "framework-style" code.
	 *
	 * <p>
	 * This method is slow and expensive (possibly tens of milliseconds)
	 * but the returned object is efficient.
	 * This is intended to be called during initialization, in order to (for example)
	 * initialize singletons for dependency injection.
	 *
	 * @param refsClass an interface class whose methods are annotated with {@link ReferencePath}.
	 * @return an object implementing <code>refsClass</code> with methods that return the desired references.
	 * @throws InvalidTypeException if any of the requested references are not valid
	 */
	<T> T buildReferences(Class<T> refsClass) throws InvalidTypeException;

	/**
	 * @return a Reference to the object at the given <code>path</code>. {@link Reference#targetType()} will return the actual type of the target object (which may or may not be <code>requestedClass</code>).
	 * @throws InvalidTypeException if the {@link Reference#targetType() target type} of the resulting Reference does not conform to <code>requestedClass</code>.
	 */
	<T> Reference<T> then(Class<T> targetClass, Path path) throws InvalidTypeException;

	<E extends Entity> CatalogReference<E> thenCatalog(Class<E> entryClass, Path path) throws InvalidTypeException;
	<E extends Entity> ListingReference<E> thenListing(Class<E> entryClass, Path path) throws InvalidTypeException;
	<K extends Entity,V> SideTableReference<K,V> thenSideTable(Class<K> keyClass, Class<V> valueClass, Path path) throws InvalidTypeException;
	<T> Reference<Reference<T>> thenReference(Class<T> targetClass, Path path) throws InvalidTypeException;

	BoskDiagnosticContext diagnosticContext();
}
