package org.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.mongo.Formatter.TenantFields;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.vena.bosk.drivers.mongo.Formatter.referenceTo;

/**
 * Implementation of {@link MongoReceiver} using a MongoDB change stream cursor.
 *
 * @author pdoyle
 */
final class ChangeStreamMongoReceiver<R extends Entity> implements MongoReceiver<R> {
	private final Formatter formatter;
	private final BoskDriver<R> downstream;
	private final Reference<R> rootRef;

	private final ExecutorService ex = Executors.newFixedThreadPool(1);
	private final ConcurrentHashMap<String, BlockingQueue<BsonDocument>> echoListeners = new ConcurrentHashMap<>();
	private final MongoCursor<ChangeStreamDocument<Document>> eventCursor;

	private final static String RESUME_TOKEN_KEY = "_data";
	private volatile BsonDocument lastProcessedResumeToken;

	ChangeStreamMongoReceiver(BoskDriver<R> downstream, Reference<R> rootRef, MongoCollection<Document> collection, Formatter formatter) {
		this.downstream = downstream;
		this.rootRef = rootRef;
		this.formatter = formatter;

		this.lastProcessedResumeToken = new BsonDocument();
		lastProcessedResumeToken.put(RESUME_TOKEN_KEY, new BsonString("0"));

		eventCursor = collection.watch().iterator();
		ex.submit(this::eventProcessingLoop);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public void flushDownstream() throws InterruptedException {
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
		currentThread().setName(getClass().getSimpleName());
		try {
			while (!ex.isShutdown()) {
				LOGGER.debug("- Awaiting event");
				ChangeStreamDocument<Document> event = eventCursor.next();
				try {
					processEvent(event);
				} catch (Throwable e) {
					LOGGER.error("Unable to process event: " + event, e);
					// TODO: How to handle this? For now, just keep soldiering on
				}
			}
		} finally {
			LOGGER.warn("Terminating MongoDriver event processing thread {}", currentThread().getName());
			currentThread().setName(oldName);
		}
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
		eventCursor.close();
		ex.shutdown();
		try {
			boolean success = ex.awaitTermination(10, SECONDS);
			if (!success) {
				LOGGER.warn("Timeout during shutdown");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Interrupted during shutdown", e);
		}
	}

	private void processEvent(ChangeStreamDocument<Document> event) {
		LOGGER.debug("# EVENT: {}", event);
		switch (event.getOperationType()) {
			case INSERT: case REPLACE:// Both of these represent replacing the whole tenant document
				// getFullDocument is reliable for INSERT and REPLACE operations:
				//   https://docs.mongodb.com/v4.0/reference/change-events/#change-stream-output
				LOGGER.debug("| Replace tenant - IGNORE");
				//driver.submitReplacement(rootRef, document2object(event.getFullDocument().get(TenantFields.root), rootRef));
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
				if (dottedName.startsWith(TenantFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						throw new IllegalStateException("Update of invalid field \"" + dottedName + "\"", e);
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
					throw new IllegalStateException("Delete of invalid field \"" + dottedName + "\"", e);
				}
				LOGGER.debug("| Delete {}", ref);
				downstream.submitDeletion(ref);
			}
		}
	}

	private void notifyIfEcho(@Nullable BsonDocument updatedFields, BsonDocument resumeToken) {
		if (updatedFields != null) {
			BsonValue newValue = updatedFields.get(TenantFields.echo.name());
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

	private static final Logger LOGGER = LoggerFactory.getLogger(ChangeStreamMongoReceiver.class);
}
