package io.vena.bosk.drivers.mongo.v2;

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
