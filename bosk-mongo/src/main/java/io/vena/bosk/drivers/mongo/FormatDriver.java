package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.exceptions.InitializationFailureException;
import java.io.IOException;
import java.lang.reflect.Type;
import org.bson.BsonDocument;
import org.bson.BsonInt64;

/**
 * Additional {@link MongoDriver} functionality that the format-specific drivers must implement.
 * <p>
 * Implementations of this are responsible for the following:
 * <ol><li>
 *     Serializing and deserializing the database contents
 * </li><li>
 *     Processing change stream events via {@link #onEvent}
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
interface FormatDriver<R extends StateTreeNode> extends MongoDriver<R> {
	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException;

	/**
	 * Implementations should ignore subsequent calls to {@link #onEvent}
	 * associated with revisions less than or equal to <code>revision</code>.
	 * <p>
	 * TODO: This feels pretty lame. Need a better way to get FormatDriver to cope with its own revision numbers
	 * @param revision the last revision to skip
	 */
	void onRevisionToSkip(BsonInt64 revision);

	/**
	 * @throws UninitializedCollectionException if it looks like the database has not yet
	 * been created (as opposed to being in a damaged or unrecognizable state).
	 * This signals to {@link MainDriver} that it may, if appropriate,
	 * automatically initialize the collection.
	 */
	StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException;

	/**
	 * Can assume there is an active database transaction.
	 * <p>
	 * Can assume that the collection is empty or nonexistent,
	 * in the sense that there is no mess to clean up,
	 * but should tolerate documents already existing,
	 * by using upsert or replace operations, for example.
	 * @param priorContents the desired state, with metadata representing a (possibly hypothetical)
	 * "prior" state of the database; in particular, the revision number should be incremented.
	 */
	void initializeCollection(StateAndMetadata<R> priorContents) throws InitializationFailureException;

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
