package org.vena.bosk.exceptions;

/**
 * Indicates a {@link org.vena.bosk.Path} was encountered that is well-formed, but invalid in context.
 */
public class UnexpectedPathException extends IllegalArgumentException {
	public UnexpectedPathException(String message) { super(message); }
	public UnexpectedPathException(Throwable cause) { super(cause); }
	public UnexpectedPathException(String message, Throwable cause) { super(message, cause); }
}
