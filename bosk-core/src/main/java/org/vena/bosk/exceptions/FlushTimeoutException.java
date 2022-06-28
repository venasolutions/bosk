package org.vena.bosk.exceptions;

import java.io.IOException;

public class FlushTimeoutException extends IOException {
	public FlushTimeoutException(String message) { super(message); }
	public FlushTimeoutException(String message, Throwable cause) { super(message, cause); }
	public FlushTimeoutException(Throwable cause) { super(cause); }
}
