package io.vena.bosk.drivers.mongo;

/**
 * Indicates that we are unable to interpret the contents of
 * the manifest document found in the database.
 */
class UnrecognizedFormatException extends Exception {
	public UnrecognizedFormatException(String message) {
		super(message);
	}

	public UnrecognizedFormatException(String message, Throwable cause) {
		super(message, cause);
	}

	public UnrecognizedFormatException(Throwable cause) {
		super(cause);
	}
}
