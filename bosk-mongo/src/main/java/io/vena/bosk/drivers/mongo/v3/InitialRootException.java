package io.vena.bosk.drivers.mongo.v3;

import io.vena.bosk.BoskDriver;

/**
 * Thrown from {@link ChangeListener#onConnectionSucceeded()} to indicate that the {@link BoskDriver#initialRoot} call failed.
 */
public class InitialRootException extends Exception {
	public InitialRootException(String message) {
		super(message);
	}

	public InitialRootException(String message, Throwable cause) {
		super(message, cause);
	}

	public InitialRootException(Throwable cause) {
		super(cause);
	}
}
