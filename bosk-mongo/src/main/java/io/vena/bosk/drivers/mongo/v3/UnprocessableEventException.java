package io.vena.bosk.drivers.mongo.v3;

import com.mongodb.client.model.changestream.OperationType;

/**
 * Indicate that no {@link FormatDriver} could cope with a particular
 * change stream event. The framework responds with a (potentially expensive)
 * reload operation that avoids attempting to re-process that event;
 * in other words, using resume tokens would never be appropriate for these.
 *
 * @see UnexpectedEventProcessingException
 */
public class UnprocessableEventException extends Exception {
	public final OperationType operationType;

	public UnprocessableEventException(String message, OperationType operationType) {
		super(message + " (" + operationType + ")");
		this.operationType = operationType;
	}

	public UnprocessableEventException(String message, Throwable cause, OperationType operationType) {
		super(message, cause);
		this.operationType = operationType;
	}

	public UnprocessableEventException(Throwable cause, OperationType operationType) {
		super(cause);
		this.operationType = operationType;
	}
}
