package io.vena.bosk.drivers.mongo;

import java.io.IOException;

public class CannotOpenSessionException extends IOException {
	public CannotOpenSessionException(String message) {
		super(message);
	}

	public CannotOpenSessionException(String message, Throwable cause) {
		super(message, cause);
	}

	public CannotOpenSessionException(Throwable cause) {
		super(cause);
	}
}
