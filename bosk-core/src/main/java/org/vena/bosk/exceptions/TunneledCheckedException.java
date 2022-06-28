package org.vena.bosk.exceptions;

/**
 * Allows a checked exception to be thrown in a context that otherwise doesn't
 * allow it, like in a method that takes a lambda that isn't declared to throw
 * an exception.
 *
 * <p>
 * The caller must catch this exception and throw {@link #getCause()}.
 *
 * @author pdoyle
 */
@SuppressWarnings("serial")
public class TunneledCheckedException extends RuntimeException {
	public TunneledCheckedException(Exception cause) {
		super(cause);
	}

	@Override
	public synchronized Exception getCause() {
		return (Exception)super.getCause();
	}

	public synchronized <T extends Exception> T getCause(Class<T> type) {
		return type.cast(super.getCause());
	}

}