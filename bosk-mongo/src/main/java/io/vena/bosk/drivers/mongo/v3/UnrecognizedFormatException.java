package io.vena.bosk.drivers.mongo.v3;

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
