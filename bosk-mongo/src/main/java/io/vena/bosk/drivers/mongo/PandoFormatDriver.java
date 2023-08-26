package io.vena.bosk.drivers.mongo;

import com.mongodb.ClientSessionOptions;
import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.lang.Nullable;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ZERO;
import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameOf;
import static io.vena.bosk.drivers.mongo.Formatter.enclosingReference;
import static io.vena.bosk.drivers.mongo.Formatter.referenceTo;
import static io.vena.bosk.drivers.mongo.MainDriver.MANIFEST_ID;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static org.bson.BsonBoolean.FALSE;

/**
 * A {@link FormatDriver} that stores the entire bosk state in a single document.
 */
final class PandoFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	private final String description;
	private final MongoDriverSettings settings;
	private final Formatter formatter;
	private final MongoClient mongoClient;
	private final MongoCollection<Document> collection;
	private final Reference<R> rootRef;
	private final BoskDriver<R> downstream;
	private final FlushLock flushLock;
	private final BsonSurgeon bsonSurgeon;

	private volatile BsonInt64 revisionToSkip = null;

	static final BsonString ROOT_DOCUMENT_ID = new BsonString("boskDocument");

	PandoFormatDriver(
		Bosk<R> bosk,
		MongoCollection<Document> collection,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin,
		MongoClient mongoClient,
		FlushLock flushLock,
		BoskDriver<R> downstream,
		List<Reference<? extends EnumerableByIdentifier<?>>> separateCollections
	) {
		this.description = PandoFormatDriver.class.getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.mongoClient = mongoClient;
		this.formatter = new Formatter(bosk, bsonPlugin);
		this.collection = collection;
		this.rootRef = bosk.rootReference();
		this.downstream = downstream;
		this.flushLock = flushLock;
		this.bsonSurgeon = new BsonSurgeon(separateCollections);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		try (Transaction txn = new Transaction()) {
			if (value instanceof BsonDocument) {
				// TODO: write out part documents
			}
			doUpdate(replacementDoc(target, value), standardPreconditions(target));
			txn.commit();
		}
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		BsonDocument filter = standardPreconditions(target);
		filter.put(dottedFieldNameOf(target, rootRef), new BsonDocument("$exists", FALSE));
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		try (Transaction txn = new Transaction()) {
			if (value instanceof BsonDocument) {
				// TODO: write out part documents
			}
			if (doUpdate(replacementDoc(target, value), filter)) {
				LOGGER.debug("| Object initialized");
			} else {
				LOGGER.debug("| No update");
			}
			txn.commit();
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (Transaction txn = new Transaction()) {
			doUpdate(deletionDoc(target), standardPreconditions(target));
			// TODO: Delete part documents
			txn.commit();
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		try (Transaction txn = new Transaction()) {
			if (value instanceof BsonDocument) {
				// TODO: write out part documents
			}
			doUpdate(
				replacementDoc(target, value),
				explicitPreconditions(target, precondition, requiredValue));
			txn.commit();
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (Transaction txn = new Transaction()) {
			doUpdate(
				deletionDoc(target),
				explicitPreconditions(target, precondition, requiredValue));
			// TODO: Delete part documents
			txn.commit();
		}
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		flushLock.awaitRevision(readRevisionNumber());
		LOGGER.debug("| Flush downstream");
		downstream.flush();
	}

	@Override
	public void close() {
		LOGGER.debug("+ close()");
		flushLock.close();
	}

	@Override
	public StateAndMetadata<R> loadAllState() throws IOException, UninitializedCollectionException {
		try (MongoCursor<Document> cursor = collection
			.withReadConcern(LOCAL) // The revision field needs to be the latest
			.find(rootDocumentFilter()).limit(1) // TODO: load all docs and use bsonSurgeon to combine them
			.cursor()
		) {
			Document document = cursor.next();
			Document state = document.get(DocumentFields.state.name(), Document.class);
			Long revision = document.get(DocumentFields.revision.name(), 0L);
			if (state == null) {
				throw new IOException("No existing state in document");
			} else {
				R root = formatter.document2object(state, rootRef);
				BsonInt64 rev = new BsonInt64(revision);
				return new StateAndMetadata<>(root, rev);
			}
		} catch (NoSuchElementException e) {
			throw new UninitializedCollectionException("No existing document", e);
		}

	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		BsonValue initialState = formatter.object2bsonValue(priorContents.state, rootRef.targetType());
		BsonInt64 newRevision = new BsonInt64(1 + priorContents.revision.longValue());
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision));
		BsonDocument filter = rootDocumentFilter();
		UpdateOptions options = new UpdateOptions().upsert(true);

		try (Transaction txn = new Transaction()) {
			if (initialState instanceof BsonDocument) {
				// TODO: write out part documents
			}
			LOGGER.debug("** Initial tenant upsert for {}", ROOT_DOCUMENT_ID);
			LOGGER.trace("| Filter: {}", filter);
			LOGGER.trace("| Update: {}", update);
			LOGGER.trace("| Options: {}", options);
			UpdateResult result = collection.updateOne(filter, update, options);
			LOGGER.debug("| Result: {}", result);
			if (settings.experimental().manifestMode() == ManifestMode.ENABLED) {
				writeManifest();
			}
			txn.commit();
		}
	}

	private void writeManifest() {
		BsonDocument doc = new BsonDocument("_id", MANIFEST_ID);
		doc.putAll((BsonDocument) formatter.object2bsonValue(Manifest.forPando(), Manifest.class));
		BsonDocument update = new BsonDocument("$set", doc);
		BsonDocument filter = new BsonDocument("_id", MANIFEST_ID);
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.debug("| Initial manifest: {}", doc);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Manifest result: {}", result);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection}.
	 */
	@Override
	public void onEvent(ChangeStreamDocument<Document> event) throws UnprocessableEventException {
		if (settings.experimental().manifestMode() == ManifestMode.ENABLED) {
			if (event.getDocumentKey() == null) {
				throw new UnprocessableEventException("Null document key", event.getOperationType());
			}
			if (MANIFEST_ID.equals(event.getDocumentKey().get("_id"))) {
				onManifestEvent(event);
				return;
			}
		}
		if (!DOCUMENT_FILTER.equals(event.getDocumentKey())) {
			LOGGER.debug("Ignoring event for unrecognized document key: {}", event.getDocumentKey());
			return;
		}
		switch (event.getOperationType()) {
			case INSERT: case REPLACE: {
				Document fullDocument = event.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument", event.getOperationType());
				}
				BsonInt64 revision = getRevisionFromFullDocumentEvent(fullDocument);
				Document state = fullDocument.get(DocumentFields.state.name(), Document.class);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", event.getOperationType());
				}
				R newRoot = formatter.document2object(state, rootRef);
				// TODO: Queue up part documents, and submit downstream only once the main event arrives
				LOGGER.debug("| Replace {}", rootRef);
				downstream.submitReplacement(rootRef, newRoot);
				flushLock.finishedRevision(revision);
			} break;
			case UPDATE: {
				// TODO: Include any queued up part documents
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					BsonInt64 revision = getRevisionFromUpdateEvent(event);
					if (shouldNotSkip(revision)) {
						replaceUpdatedFields(updateDescription.getUpdatedFields());
						deleteRemovedFields(updateDescription.getRemovedFields(), event.getOperationType());
					}
					flushLock.finishedRevision(revision);
				}
			} break;
			case DELETE: {
				LOGGER.debug("Document containing revision field has been deleted; assuming revision=0");
				flushLock.finishedRevision(REVISION_ZERO);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", event.getOperationType());
			}
		}
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection},
	 * but outside that, we want to be as strict as we can
	 * so incompatible database changes don't go unnoticed.
	 */
	private static void onManifestEvent(ChangeStreamDocument<Document> event) throws UnprocessableEventException {
		if (event.getOperationType() == INSERT) {
			Document manifest = requireNonNull(event.getFullDocument());
			try {
				MainDriver.validateManifest(manifest);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!new Document().equals(manifest.get("pando"))) {
				throw new UnprocessableEventException("Unexpected value in manifest \"pando\" field: " + manifest.get("pando"), event.getOperationType());
			}
		} else {
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		revisionToSkip = revision;
		flushLock.finishedRevision(revision);
	}

	private BsonInt64 getRevisionFromFullDocumentEvent(Document fullDocument) {
		if (fullDocument == null) {
			return null;
		}
		Long revision = fullDocument.getLong(DocumentFields.revision.name());
		if (revision == null) {
			return null;
		} else {
			return new BsonInt64(revision);
		}
	}

	private static BsonInt64 getRevisionFromUpdateEvent(ChangeStreamDocument<Document> event) {
		if (event == null) {
			return null;
		}
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription == null) {
			return null;
		}
		BsonDocument updatedFields = updateDescription.getUpdatedFields();
		if (updatedFields == null) {
			return null;
		}
		return updatedFields.getInt64(DocumentFields.revision.name(), null);
	}

	//
	// MongoDB helpers
	//

	/**
	 * @return Non-null revision number as per the database.
	 * If the database contains no revision number, returns {@link Formatter#REVISION_ZERO}.
	 */
	private BsonInt64 readRevisionNumber() throws FlushFailureException {
		LOGGER.debug("readRevisionNumber");
		try {
			try (MongoCursor<Document> cursor = collection
				.withReadConcern(LOCAL) // The revision field needs to be the latest
				.find(DOCUMENT_FILTER)
				.limit(1)
				.projection(fields(include(DocumentFields.revision.name())))
				.cursor()
			) {
				Document doc = cursor.next();
				Long result = doc.get(DocumentFields.revision.name(), Long.class);
				if (result == null) {
					// Document exists but has no revision field.
					// In that case, newer servers (including this one) will create the
					// the field upon initialization, and we're ok to wait for any old
					// revision number at all.
					LOGGER.debug("No revision field; assuming {}", REVISION_ZERO.longValue());
					return REVISION_ZERO;
				} else {
					LOGGER.debug("Read revision {}", result);
					return new BsonInt64(result);
				}
			}
		} catch (NoSuchElementException e) {
			LOGGER.debug("Document is missing", e);
			throw new RevisionFieldDisruptedException(e);
		} catch (RuntimeException e) {
			LOGGER.debug("readRevisionNumber failed", e);
			throw new FlushFailureException(e);
		}
	}

	private BsonDocument rootDocumentFilter() {
		return new BsonDocument("_id", ROOT_DOCUMENT_ID);
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target) {
		BsonDocument filter = rootDocumentFilter();
		if (!target.path().isEmpty()) {
			String enclosingObjectKey = dottedFieldNameOf(enclosingReference(target), rootRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
	}

	private <T> BsonDocument explicitPreconditions(Reference<T> target, Reference<Identifier> preconditionRef, Identifier requiredValue) {
		BsonDocument filter = standardPreconditions(target);
		BsonDocument precondition = new BsonDocument("$eq", new BsonString(requiredValue.toString()));
		filter.put(dottedFieldNameOf(preconditionRef, rootRef), precondition);
		return filter;
	}

	private <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value) {
		String key = dottedFieldNameOf(target, rootRef);
		LOGGER.debug("| Set field {}: {}", key, value);
		return updateDoc()
			.append("$set", new BsonDocument(key, value));
	}

	private <T> BsonDocument deletionDoc(Reference<T> target) {
		String key = dottedFieldNameOf(target, rootRef);
		LOGGER.debug("| Unset field {}", key);
		return updateDoc().append("$unset", new BsonDocument(key, new BsonNull())); // Value is ignored
	}

	private BsonDocument updateDoc() {
		return new BsonDocument("$inc", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(1)));
	}

	private BsonDocument initialDocument(BsonValue initialState, BsonInt64 revision) {
		BsonDocument fieldValues = new BsonDocument("_id", ROOT_DOCUMENT_ID);

		fieldValues.put(DocumentFields.path.name(), new BsonString("/"));
		fieldValues.put(DocumentFields.state.name(), initialState);
		fieldValues.put(DocumentFields.revision.name(), revision);

		return fieldValues;
	}

	/**
	 * @return true if something changed
	 */
	private boolean doUpdate(BsonDocument updateDoc, BsonDocument filter) {
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		if (settings.testing().eventDelayMS() < 0) {
			LOGGER.debug("| Sleeping");
			try {
				Thread.sleep(-settings.testing().eventDelayMS());
			} catch (InterruptedException e) {
				LOGGER.debug("| Interrupted");
			}
		}
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		if (result.wasAcknowledged()) {
			assert result.getMatchedCount() <= 1;
			return result.getMatchedCount() >= 1;
		} else {
			LOGGER.error("MongoDB write was not acknowledged");
			LOGGER.trace("Details of MongoDB write not acknowledged:\n\tFilter: {}\n\tUpdate: {}\n\tResult: {}", filter, updateDoc, result);
			throw new IllegalStateException("Mongo write was not acknowledged: " + result);
		}
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

	private boolean shouldNotSkip(BsonInt64 revision) {
		return revision == null || revisionToSkip == null || revision.longValue() > revisionToSkip.longValue();
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(@Nullable List<String> removedFields, OperationType operationType) throws UnprocessableEventException {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Delete {}", ref);
					downstream.submitDeletion(ref);
				} else {
					throw new UnprocessableEventException("Deletion of metadata field " + dottedName, operationType);
				}
			}
		}
	}

	private class Transaction implements AutoCloseable {
		private final ClientSession session;

		Transaction() {
			ClientSessionOptions sessionOptions = ClientSessionOptions.builder()
				.causallyConsistent(true)
				.defaultTransactionOptions(TransactionOptions.builder()
					.writeConcern(WriteConcern.MAJORITY)
					.readConcern(ReadConcern.MAJORITY)
					.build())
				.build();
			session = mongoClient.startSession(sessionOptions);
		}

		public void commit() {
			session.commitTransaction();
		}

		@Override
		public void close() {
			session.close();
		}
	}

	private void logNonexistentField(String dottedName, InvalidTypeException e) {
		LOGGER.trace("Nonexistent field {}",  dottedName, e);
		if (LOGGER.isWarnEnabled() && ALREADY_WARNED.add(dottedName)) {
			LOGGER.warn("Ignoring updates of nonexistent field {}", dottedName);
		}
	}

	@Override
	public String toString() {
		return description;
	}

	private static final Set<String> ALREADY_WARNED = newSetFromMap(new ConcurrentHashMap<>());
	private static final BsonDocument DOCUMENT_FILTER = new BsonDocument("_id", ROOT_DOCUMENT_ID);
	private static final Logger LOGGER = LoggerFactory.getLogger(PandoFormatDriver.class);
}
