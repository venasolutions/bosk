package io.vena.bosk.drivers.mongo.v3;

import com.mongodb.client.model.changestream.ChangeStreamDocument;

/**
 * Indicates that a {@link RuntimeException} was thrown by
 * {@link ChangeListener#onEvent(ChangeStreamDocument)}.
 *
 * @see UnprocessableEventException
 */
public class UnexpectedEventProcessingException extends Exception {
	public UnexpectedEventProcessingException(Throwable cause) {
		super(cause);
	}

	public UnexpectedEventProcessingException(String message, Throwable cause) {
		super(message, cause);
	}
}
