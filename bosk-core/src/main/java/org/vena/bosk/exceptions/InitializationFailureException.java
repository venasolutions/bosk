package org.vena.bosk.exceptions;

import java.io.IOException;
import org.vena.bosk.BoskDriver;

/**
 * Indicates that a call to {@link BoskDriver#initialRoot} is unable to return an
 * appropriate result due to runtime circumstances.
 *
 * <p>
 * (Errors involving misuse or misconfiguration that could not possibly have worked
 * under any runtime circumstances are encouraged to throw other exceptions like
 * {@link IllegalArgumentException} or {@link InvalidTypeException}).
 *
 * <p>
 * Useful as a wrapper for other kinds of checked exceptions that could be thrown
 * from {@link BoskDriver} implementations.
 *
 * <p>
 * Extends {@link IOException} because we expect that any code that already
 * handles that will do the right thing for this (eg. aborting, retrying, logging).
 * The same is not necessarily true for {@link RuntimeException}.
 */
public class InitializationFailureException extends IOException {
	public InitializationFailureException(String message) { super(message); }
	public InitializationFailureException(String message, Throwable cause) { super(message, cause); }
	public InitializationFailureException(Throwable cause) { super(cause); }
}
