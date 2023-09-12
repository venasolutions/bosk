package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.bson.BsonDocument;

interface ChangeListener {
	void onConnectionSucceeded() throws
		UnrecognizedFormatException,
		UninitializedCollectionException,
		InterruptedException,
		IOException,
		InitialRootActionException,
		TimeoutException;

	void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException;

	void onConnectionFailed(Exception e) throws InterruptedException, InitialRootActionException, TimeoutException;
	void onDisconnect(Throwable e);
}
