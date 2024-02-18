package io.vena.bosk.drivers.mongo;

/**
 * Indicates that the database has been found to be in a state that
 * could be considered "uninitialized", in the sense that we are permitted
 * to respond by automatically initializing the database.
 * <p>
 * This is not the same as discovering that the database is simply in some unexpected state,
 * in which case other exceptions may be thrown and the driver would likely disconnect
 * rather than overwrite the database contents.
 * Rather, we must have a fairly high degree of certainty that we're ok to start fresh.
 */
class UninitializedCollectionException extends Exception {
	public UninitializedCollectionException() {
	}

	public UninitializedCollectionException(String message) {
		super(message);
	}

	public UninitializedCollectionException(String message, Throwable cause) {
		super(message, cause);
	}

	public UninitializedCollectionException(Throwable cause) {
		super(cause);
	}
}
