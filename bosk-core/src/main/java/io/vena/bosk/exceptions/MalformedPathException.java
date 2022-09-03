package io.vena.bosk.exceptions;

public class MalformedPathException extends IllegalArgumentException {
	public MalformedPathException(String message) { super(message); }
	public MalformedPathException(Throwable cause) { super(cause); }
	public MalformedPathException(String message, Throwable cause) { super(message, cause); }
}
