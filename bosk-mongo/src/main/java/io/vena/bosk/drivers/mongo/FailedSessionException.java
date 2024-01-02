package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoClient;
import java.io.IOException;

/**
 * Indicates a call to {@link MongoClient#startSession()} threw an exception,
 * leaving us unable to interact with the database. This is often a transient
 * failure that could be remedied by retrying.
 */
public class FailedSessionException extends IOException {
	public FailedSessionException(String message) {
		super(message);
	}

	public FailedSessionException(String message, Throwable cause) {
		super(message, cause);
	}

	public FailedSessionException(Throwable cause) {
		super(cause);
	}
}
