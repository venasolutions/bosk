package io.vena.bosk.exceptions;

public class ParameterAlreadyBoundException extends RuntimeException {
	public ParameterAlreadyBoundException(String message) { super(message); }
	public ParameterAlreadyBoundException(String message, Throwable cause) { super(message, cause); }
	public ParameterAlreadyBoundException(Throwable cause) { super(cause); }
}
