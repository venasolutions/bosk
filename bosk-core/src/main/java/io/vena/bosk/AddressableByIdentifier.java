package io.vena.bosk;

interface AddressableByIdentifier<T> {
	/**
	 * @return The item with the given <code>id</code>, or null if no such item exists.
	 */
	T get(Identifier id);
}
