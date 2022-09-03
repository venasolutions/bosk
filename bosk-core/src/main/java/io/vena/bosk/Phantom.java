package io.vena.bosk;

/**
 * Used to create a thing that can be referenced (eg. as the {@link Listing#domain() domain}
 * of a {@link Listing}) but that is not actually serialized or instantiated.
 *
 * <p>
 * Behaves like an {@link java.util.Optional Optional} that is always empty.
 */
public final class Phantom<T> {
	public static <T> Phantom<T> empty() {
		@SuppressWarnings("unchecked")
		Phantom<T> instance = (Phantom<T>) INSTANCE;
		return instance;
	}
	private static final Phantom<?> INSTANCE = new Phantom<>();
}
