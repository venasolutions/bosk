package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Entity;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.exceptions.InitializationFailureException;
import java.io.IOException;
import java.lang.reflect.Type;
import org.bson.BsonInt64;
import org.bson.Document;

/**
 * Additional {@link MongoDriver} functionality that the format-specific drivers must implement.
 * <p>
 * Implementations of this are responsible for the following:
 * <ol><li>
 *     Serializing and deserializing the database contents
 * </li><li>
 *     Processing change stream events via {@link #onEvent}
 * </li><li>
 *     Managing revision numbers (eg. {@link #onRevisionToSkip}
 * </li><li>
 *     Implementing {@link #flush()} (consider using {@link FlushLock})
 * </li></ol>
 *
 * Implementations are not responsible for:
 * <ol><li>
 *     Handling exceptions from {@link MongoClient} or {@link MongoChangeStreamCursor}
 * </li><li>
 *     Determining the database format
 * </li><li>
 *     Implementing {@link #initialRoot} or {@link #refurbish()}
 * </li></ol>
 */
interface FormatDriver<R extends Entity> extends MongoDriver<R> {
	void onEvent(ChangeStreamDocument<Document> event);

	/**
	 * Implementations should ignore subsequent calls to {@link #onEvent}
	 * associated with revisions less than or equal to <code>revision</code>.
	 * @param revision the last revision to skip
	 */
	void onRevisionToSkip(BsonInt64 revision);

	StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException;

	/**
	 * Can assume that the collection is empty or nonexistent.
	 * Can assume it's called in a transaction.
	 */
	void initializeCollection(StateAndMetadata<R> contents) throws InitializationFailureException;

	default boolean isDisconnected() { return false; }

	@Override
	default R initialRoot(Type rootType) {
		throw new UnsupportedOperationException(
			"FormatDriver doesn't need to implement initialRoot: MainDriver derives it from loadAllState");
	}

	@Override
	default void refurbish() {
		throw new UnsupportedOperationException(
			"FormatDriver doesn't need to implement refurbish: MainDriver derives it from loadAllState and initializeCollection");
	}
}
