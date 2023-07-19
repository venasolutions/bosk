package io.vena.bosk.drivers.mongo;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode;

/**
 * Thrown from {@link BoskDriver#initialRoot} if the initial root
 * can't be loaded from the database and {@link InitialDatabaseUnavailableMode#FAIL}
 * is in effect.
 */
public class InitialRootFailureException extends RuntimeException {
	public InitialRootFailureException(String message) {
		super(message);
	}

	public InitialRootFailureException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialRootFailureException(Throwable cause) {
		super(cause);
	}
}
