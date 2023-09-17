package io.vena.bosk.drivers.mongo;

public class FormatMisconfigurationException extends IllegalArgumentException {
	public FormatMisconfigurationException(String s) {
		super(s);
	}

	public FormatMisconfigurationException(String message, Throwable cause) {
		super(message, cause);
	}

	public FormatMisconfigurationException(Throwable cause) {
		super(cause);
	}
}
