package io.vena.bosk.drivers.mongo.v2;

import java.io.IOException;

class UnrecognizedFormatException extends IOException {
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
