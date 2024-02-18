package io.vena.bosk;

sealed interface AddressableByIdentifier<T> permits EnumerableByIdentifier {
	/**
	 * @return The item with the given <code>id</code>, or null if no such item exists.
	 */
	T get(Identifier id);
}
