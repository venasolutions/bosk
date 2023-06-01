package io.vena.bosk.drivers.mongo.v2;

public class DisconnectedException extends RuntimeException {
	public DisconnectedException() {
	}

	public DisconnectedException(String message) {
		super(message);
	}

	public DisconnectedException(String message, Throwable cause) {
		super(message, cause);
	}

	public DisconnectedException(Throwable cause) {
		super(cause);
	}
}
