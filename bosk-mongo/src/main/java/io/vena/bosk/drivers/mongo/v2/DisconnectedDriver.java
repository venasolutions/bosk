package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import java.io.IOException;
import org.bson.BsonInt64;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DisconnectedDriver<R extends Entity> implements FormatDriver<R> {
	@Override
	public boolean isDisconnected() {
		return true;
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		throw disconnected("submitReplacement");
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected("submitConditionalReplacement");
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		throw disconnected("submitInitialization");
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		throw disconnected("submitDeletion");
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected("submitConditionalDeletion");
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		throw disconnected("flush");
	}

	@Override
	public void close() { }

	@Override
	public StateAndMetadata<R> loadAllState() {
		throw disconnected("loadAllState");
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> contents) {

	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		throw new AssertionError("Resynchronization should not tell DisconnectedDriver to skip a revision");
	}

	private DisconnectedException disconnected(String name) {
		return new DisconnectedException("Disconnected driver cannot execute " + name);
	}

	@Override
	public void onEvent(ChangeStreamDocument<Document> event) {
		LOGGER.info("Event received in disconnected mode: {} {}", event.getOperationType(), event.getResumeToken());
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectedDriver.class);
}
