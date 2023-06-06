package io.vena.bosk.drivers.mongo.v2;

public class UnprocessableEventException extends Exception {
	public UnprocessableEventException(String message) {
		super(message);
	}

	public UnprocessableEventException(String message, Throwable cause) {
		super(message, cause);
	}

	public UnprocessableEventException(Throwable cause) {
		super(cause);
	}
}
