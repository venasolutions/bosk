package io.vena.bosk.drivers.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

	public static final Logger LOGGER = LoggerFactory.getLogger(UninitializedCollectionException.class);
}
