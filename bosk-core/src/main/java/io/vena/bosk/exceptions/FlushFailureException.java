package io.vena.bosk.exceptions;

import io.vena.bosk.BoskDriver;
import java.io.IOException;

/**
 * Indicates that a call to {@link BoskDriver#flush()} was unable to guarantee
 * that all prior updates have been applied.
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
public class FlushFailureException extends IOException {
	public FlushFailureException(String message) { super(message); }
	public FlushFailureException(String message, Throwable cause) { super(message, cause); }
	public FlushFailureException(Throwable cause) { super(cause); }
}
