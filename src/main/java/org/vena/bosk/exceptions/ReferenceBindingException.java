package org.vena.bosk.exceptions;

public class ReferenceBindingException extends RuntimeException {
	public ReferenceBindingException(String message) { super(message); }
	public ReferenceBindingException(Throwable cause) { super(cause); }
	public ReferenceBindingException(String message, Throwable cause) { super(message, cause); }
}
