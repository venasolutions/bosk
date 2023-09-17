package io.vena.bosk.drivers.mongo;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.exceptions.InitializationFailureException;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
class DisconnectedDriver<R extends StateTreeNode> implements FormatDriver<R> {
	private final Throwable reason;
	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		throw disconnected();
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		throw disconnected();
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		throw disconnected();
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		throw disconnected();
	}

	@Override
	public void close() {
		// Nothing to do
	}

	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) {
		LOGGER.debug("Already disconnected; ignoring event ({})", event.getOperationType().getValue());
	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		throw new AssertionError("Resynchronization should not tell DisconnectedDriver to skip a revision");
	}

	@Override
	public StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException {
		throw disconnected();
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) throws InitializationFailureException {
		throw disconnected();
	}

	private DisconnectedException disconnected() {
		return new DisconnectedException(reason);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DisconnectedDriver.class);
}
