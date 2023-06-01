package io.vena.bosk.drivers.mongo.v2;

class ReceiverInitializationException extends Exception {
	public ReceiverInitializationException(Throwable cause) {
		super(cause);
	}

	public ReceiverInitializationException(String message) {
		super(message);
	}

	public ReceiverInitializationException(String message, Throwable cause) {
		super(message, cause);
	}
}
