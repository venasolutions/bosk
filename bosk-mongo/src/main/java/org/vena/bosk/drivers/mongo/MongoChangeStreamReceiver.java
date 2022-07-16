package org.vena.bosk.drivers.mongo;

import com.mongodb.MongoException;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static java.lang.String.format;
import static java.lang.System.identityHashCode;
import static java.lang.Thread.currentThread;
import static java.util.Collections.synchronizedSet;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.vena.bosk.drivers.mongo.Formatter.referenceTo;

/**
 * Implementation of {@link MongoReceiver} using a MongoDB change stream cursor.
 *
 * @author pdoyle
 */
final class MongoChangeStreamReceiver<R extends Entity> implements MongoReceiver<R> {
	private final Formatter formatter;
	private final BoskDriver<R> downstream;
	private final Reference<R> rootRef;

	private final ExecutorService ex = Executors.newFixedThreadPool(1);
	private final ConcurrentHashMap<String, BlockingQueue<BsonDocument>> echoListeners = new ConcurrentHashMap<>();
	private final MongoCollection<Document> collection;

	private final String identityString = format("%08x", identityHashCode(this));

	private volatile MongoCursor<ChangeStreamDocument<Document>> eventCursor;
	private volatile BsonDocument lastProcessedResumeToken = null;
	private volatile boolean isClosed = false;

	MongoChangeStreamReceiver(BoskDriver<R> downstream, Reference<R> rootRef, MongoCollection<Document> collection, Formatter formatter) {
		this.downstream = downstream;
		this.rootRef = rootRef;
		this.formatter = formatter;

		this.collection = collection;
		eventCursor = collection.watch().iterator();
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(
				"Initiate event processing loop for mcsr-{}: collection=\"{}\"",
				identityString,
				collection.getNamespace().getCollectionName(),
				new Exception("Here's your stack trace"));
		}
		ex.submit(this::eventProcessingLoop);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public void flushDownstream() throws InterruptedException, IOException {
		downstream.flush();
	}

	@Override
	public void putEchoListener(String echoToken, BlockingQueue<BsonDocument> listener) {
		Object existing = echoListeners.put(echoToken, listener);
		if (existing != null) {
			throw new IllegalStateException("Cannot have two listeners for the same echo token");
		}
	}

	@Override
	public BlockingQueue<BsonDocument> removeEchoListener(String echoToken) {
		return echoListeners.remove(echoToken);
	}

	//
	// Change stream event processing from MongoDB
	//

	private void eventProcessingLoop() {
		String oldName = currentThread().getName();
		currentThread().setName("mcsr-" + identityString);
		try {
			while (!ex.isShutdown()) {
				ChangeStreamDocument<Document> event;
				try {
					LOGGER.debug("- Awaiting event");
					event = eventCursor.next();
				} catch (MongoException e) {
					if (isClosed) {
						LOGGER.trace("Receiver is closed. Exiting event processing loop", e);
						break;
					} else {
						LOGGER.warn("Lost change stream cursor; reconnecting", e);
						reconnectCursor();
						continue;
					}
				}
				try {
					processEvent(event);
				} catch (Throwable e) {
					LOGGER.error("Unable to process event: " + event, e);
					// TODO: How to handle this? For now, just keep soldiering on
				}
			}
		} catch (Throwable e) {
			LOGGER.error("Fatal error on MongoDB event processing thread", e);
			throw e;
		} finally {
			LOGGER.debug("Terminating MongoDB event processing thread");
			currentThread().setName(oldName);
		}
	}

	private void reconnectCursor() {
		try {
			eventCursor.close();
		} catch (Exception e) {
			LOGGER.warn("Unable to close event stream cursor", e);
		}
		ChangeStreamIterable<Document> iterable = collection.watch();
		if (lastProcessedResumeToken == null) {
			LOGGER.error("No resume token available. Reconnecting cursor from current location. Some update events could be missed.");
		} else {
			LOGGER.debug("Attempting to reconnect cursor with resume token {}", lastProcessedResumeToken);
			iterable = iterable.resumeAfter(lastProcessedResumeToken);
		}
		eventCursor = iterable.iterator();
		LOGGER.debug("Finished reconnecting");
	}

	/**
	 * Attempts a clean shutdown of the resources used by this driver.
	 * In typical usage, a Bosk remains in use for the entire duration of
	 * the Java process, so there's no need for orderly shutdown.
	 * This method is offered on a best-effort basis for situations,
	 * such as tests, in which a clean shutdown is desired but not critical.
	 */
	@Override
	public void close() {
		isClosed = true;
		LOGGER.debug("Closing {}", identityString);
		try {
			eventCursor.close();
			ex.shutdown();
			try {
				LOGGER.debug("Awaiting termination of {}", identityString);
				boolean success = ex.awaitTermination(10, SECONDS);
				if (!success) {
					LOGGER.warn("Timeout during shutdown of {}", identityString);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.warn("Interrupted during shutdown of {}", identityString, e);
			}
		} catch (Throwable t) {
			LOGGER.error("Exception attempting to close {}", identityString, t);
			throw t;
		}
	}

	private void processEvent(ChangeStreamDocument<Document> event) {
		LOGGER.debug("# EVENT: {}", event);
		switch (event.getOperationType()) {
			case INSERT: case REPLACE:// Both of these represent replacing the whole document
				// getFullDocument is reliable for INSERT and REPLACE operations:
				//   https://docs.mongodb.com/v4.0/reference/change-events/#change-stream-output
				LOGGER.debug("| Replace document - IGNORE");
				//driver.submitReplacement(rootRef, document2object(event.getFullDocument().get(DocumentFields.root), rootRef));
				// TODO
				break;
			case UPDATE:
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(updateDescription.getUpdatedFields());
					deleteRemovedFields(updateDescription.getRemovedFields());
					notifyIfEcho(updateDescription.getUpdatedFields(), event.getResumeToken());
				}
				break;
			default:
				throw new NotYetImplementedException("Unknown change stream event: " + event);
		}
		lastProcessedResumeToken = event.getResumeToken();
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitReplacement submitReplacement}</code>
	 * for each updated field.
	 */
	private void replaceUpdatedFields(@Nullable BsonDocument updatedFields) {
		if (updatedFields != null) {
			for (Map.Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Replace {}", ref);
					Object replacement = formatter.bsonValue2object(entry.getValue(), ref);
					downstream.submitReplacement(ref, replacement);
				}
			}
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(@Nullable List<String> removedFields) {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				Reference<Object> ref;
				try {
					ref = referenceTo(dottedName, rootRef);
				} catch (InvalidTypeException e) {
					logNonexistentField(dottedName, e);
					continue;
				}
				LOGGER.debug("| Delete {}", ref);
				downstream.submitDeletion(ref);
			}
		}
	}

	private void notifyIfEcho(@Nullable BsonDocument updatedFields, BsonDocument resumeToken) {
		if (updatedFields != null) {
			BsonValue newValue = updatedFields.get(DocumentFields.echo.name());
			if (newValue != null) {
				String echoToken = newValue.asString().getValue();
				BlockingQueue<BsonDocument> listener = removeEchoListener(echoToken);
				if (listener != null) {
					LOGGER.debug("| Echo {}: {}", echoToken, resumeToken);
					listener.add(resumeToken);
				}
			}
		}
	}

	private void logNonexistentField(String dottedName, InvalidTypeException e) {
		LOGGER.trace("Nonexistent field \"" + dottedName + "\"", e);
		if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
			LOGGER.warn("Ignoring update of nonexistent field \"" + dottedName + "\"");
		}
	}

	private static final Set<String> ALREADY_WARNED = synchronizedSet(new HashSet<>());

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoChangeStreamReceiver.class);
}
