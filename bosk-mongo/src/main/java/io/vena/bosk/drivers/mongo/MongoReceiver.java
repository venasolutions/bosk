package io.vena.bosk.drivers.mongo;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import org.bson.BsonDocument;

/**
 * Sends updates to a downstream driver based on events received from MongoDB.
 * Implements the "back end" of a {@link SingleDocumentMongoDriver}, encapsulating the
 * downstream driver.
 *
 * <p>
 * Mostly operates "headless", in the sense that once established, the receiver
 * takes care of itself. However, there are a few areas where we need to give
 * instructions to the receiver:
 *
 * <ul><li>
 *     During initialization, the driver needs to call {@link #initialRoot} on the downstream driver.
 * </li><li>
 *     A {@link BoskDriver#flush} needs to call {@link #flushDownstream()}.
 * </li><li>
 *     An echo operation (which is how {@link SingleDocumentMongoDriver} implements {@link BoskDriver#flush}
 *     needs to detect echo events, so we expose a listener interface via {@link #putEchoListener}
 *     and {@link #removeEchoListener}.
 * </li><li>
 *     A {@link #close()} operation is offered for orderly shutdown, especially for testing.
 * </li></ul>
 *
 * @author pdoyle
 */
interface MongoReceiver<R extends Entity> extends Closeable {
	void close();

	// Proxied methods for downstream driver
	R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException;
	void flushDownstream() throws InterruptedException, IOException;

	/**
	 * Blocks until we've received and processed the revision corresponding to the
	 * current <code>revision</code> field in the database. Used to implement {@link BoskDriver#flush()}.
	 */
	void awaitLatestRevision() throws InterruptedException, IOException;

	/**
	 * Causes <code>listener.add(resumeToken)</code> to be called at a future time
	 * when a change stream event arrives that sets the <code>echo</code> field to the given value.
	 * @throws IllegalStateException if there's already a listener for <code>echoToken</code>.
	 */
	void putEchoListener(String echoToken, BlockingQueue<BsonDocument> listener);

	/**
	 * Under normal circumstances, this is unnecessary but harmless.
	 * Call it from a <code>finally</code> clause to make sure you don't leave a mess behind.
	 */
	BlockingQueue<BsonDocument> removeEchoListener(String echoToken);
}
