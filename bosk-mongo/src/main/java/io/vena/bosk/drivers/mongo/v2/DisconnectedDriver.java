package io.vena.bosk.drivers.mongo.v2;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.BsonInt64;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
class DisconnectedDriver<R extends Entity> implements FormatDriver<R> {
	final String reason;

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
		return new DisconnectedException("Cannot execute " + name + " while disconnected (due to: " + reason + ")");
	}

	@Override
	public void onEvent(ChangeStreamDocument<Document> event) {
		LOGGER.info("Ignoring {} event while disconnected (due to: {})", event.getOperationType(), reason);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectedDriver.class);
}
