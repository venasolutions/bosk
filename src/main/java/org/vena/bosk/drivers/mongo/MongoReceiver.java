package org.vena.bosk.drivers.mongo;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import org.bson.BsonDocument;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.exceptions.InvalidTypeException;

/**
 * Sends updates to a downstream driver based on events received from MongoDB.
 * Implements the "back end" of a {@link MongoDriver}, encapsulating the
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
 *     An echo operation (which is how {@link MongoDriver} implements {@link BoskDriver#flush}
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

	// Echo functionality to implement flush()
	void putEchoListener(String echoToken, BlockingQueue<BsonDocument> listener);
	BlockingQueue<BsonDocument> removeEchoListener(String echoToken);
}
