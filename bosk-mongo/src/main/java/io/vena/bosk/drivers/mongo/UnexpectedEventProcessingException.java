package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;

/**
 * Indicates that a {@link RuntimeException} was thrown by
 * {@link ChangeListener#onEvent(ChangeStreamDocument)}.
 *
 * @see UnprocessableEventException
 */
class UnexpectedEventProcessingException extends Exception {
	public UnexpectedEventProcessingException(Throwable cause) {
		super(cause);
	}

	public UnexpectedEventProcessingException(String message, Throwable cause) {
		super(message, cause);
	}
}
