package org.vena.bosk.exceptions;

/**
 * Indicates that a Bosk operation does not apply to the datatype to which it's being applied.
 *
 * @author pdoyle
 */
@SuppressWarnings("serial")
public class InvalidTypeException extends Exception {
	public InvalidTypeException(String message) { super(message); }
	public InvalidTypeException(String message, Throwable cause) { super(message, cause); }
}