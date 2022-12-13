package io.vena.bosk.drivers.mongo;

public class BsonFormatException extends IllegalStateException {
	public BsonFormatException(String s) { super(s); }
	public BsonFormatException(String message, Throwable cause) { super(message, cause); }
	public BsonFormatException(Throwable cause) { super(cause); }
}
