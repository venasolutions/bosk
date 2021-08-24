package org.vena.bosk.exceptions;

public class ParameterUnboundException extends RuntimeException {
	public ParameterUnboundException(String message) { super(message); }
	public ParameterUnboundException(String message, Throwable cause) { super(message, cause); }
	public ParameterUnboundException(Throwable cause) { super(cause); }
}
