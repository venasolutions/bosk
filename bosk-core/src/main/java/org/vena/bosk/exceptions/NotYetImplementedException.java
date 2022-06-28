package org.vena.bosk.exceptions;

/**
 * This is the equivalent of a todo comment -- it indicates there is some logic
 * missing.  Finished code should never throw this.
 *
 * @author Patrick Doyle
 *
 */
@SuppressWarnings("serial")
public class NotYetImplementedException extends RuntimeException {

	public NotYetImplementedException() {
		super();
	}

	public NotYetImplementedException(String message, Throwable cause) {
		super(message, cause);
	}

	public NotYetImplementedException(String message) {
		super(message);
	}

	public NotYetImplementedException(Throwable cause) {
		super(cause);
	}

}
