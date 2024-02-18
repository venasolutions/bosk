package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.lang.Nullable;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static com.mongodb.client.model.changestream.OperationType.REPLACE;
import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ZERO;
import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameOf;
import static io.vena.bosk.drivers.mongo.Formatter.enclosingReference;
import static io.vena.bosk.drivers.mongo.Formatter.referenceTo;
import static io.vena.bosk.drivers.mongo.MainDriver.MANIFEST_ID;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode.CREATE_IF_ABSENT;
import static java.util.Collections.newSetFromMap;
import static java.util.Objects.requireNonNull;
import static org.bson.BsonBoolean.FALSE;

/**
 * Implements the {@link io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat#SEQUOIA Sequoia} format.
 */
final class SequoiaFormatDriver<R extends StateTreeNode> extends AbstractFormatDriver<R> {
	private final String description;
	private final MongoDriverSettings settings;
	private final MongoCollection<BsonDocument> collection;
	private final BoskDriver<R> downstream;
	private final FlushLock flushLock;

	private volatile BsonInt64 revisionToSkip = null;

	static final BsonString DOCUMENT_ID = new BsonString("boskDocument");

	SequoiaFormatDriver(
		Bosk<R> bosk,
		MongoCollection<BsonDocument> collection,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin,
		FlushLock flushLock,
		BoskDriver<R> downstream
	) {
		super(bosk.rootReference(), new Formatter(bosk, bsonPlugin));
		this.description = getClass().getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.collection = collection;
		this.downstream = downstream;
		this.flushLock = flushLock;
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		doUpdate(replacementDoc(target, newValue), standardPreconditions(target));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		BsonDocument filter = standardPreconditions(target);
		filter.put(dottedFieldNameOf(target, rootRef), new BsonDocument("$exists", FALSE));
		if (doUpdate(replacementDoc(target, newValue), filter)) {
			LOGGER.debug("| Object initialized");
		} else {
			LOGGER.debug("| No update");
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		doUpdate(deletionDoc(target), standardPreconditions(target));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		doUpdate(
			replacementDoc(target, newValue),
			explicitPreconditions(target, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		doUpdate(
			deletionDoc(target),
			explicitPreconditions(target, precondition, requiredValue));
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
	BsonState loadBsonState() throws UninitializedCollectionException {
		try (MongoCursor<BsonDocument> cursor = collection
			.withReadConcern(LOCAL) // The revision field needs to be the latest
			.find(documentFilter())
			.limit(1)
			.cursor()
		) {
			BsonDocument document = cursor.next();
			return new BsonState(
				document.getDocument(DocumentFields.state.name(), null),
				document.getInt64(DocumentFields.revision.name(), null),
				Formatter.getDiagnosticAttributesIfAny(document)
			);
		} catch (NoSuchElementException e) {
			throw new UninitializedCollectionException("No existing document", e);
		}

	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		BsonValue initialState = formatter.object2bsonValue(priorContents.state(), rootRef.targetType());
		BsonInt64 newRevision = new BsonInt64(1 + priorContents.revision().longValue());
		// Note that priorContents.diagnosticAttributes are ignored, and we use the attributes from this thread
		BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision));
		BsonDocument filter = documentFilter();
		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.debug("** Initial upsert for {}", DOCUMENT_ID);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = collection.updateOne(filter, update, options);
		LOGGER.debug("| Result: {}", result);
		if (settings.experimental().manifestMode() == CREATE_IF_ABSENT) {
			// This is the only time Sequoia changes two documents for the same operation.
			// Aside from refurbish, it's the only reason we'd want multi-document transactions,
			// and it's not even a strong reason, because this still works correctly
			// if interpreted as two separate events.
			writeManifest();
		}
	}

	private void writeManifest() {
		assert settings.experimental().manifestMode() == CREATE_IF_ABSENT;
		BsonDocument doc = new BsonDocument("_id", MANIFEST_ID);
		doc.putAll((BsonDocument) formatter.object2bsonValue(Manifest.forSequoia(), Manifest.class));
		BsonDocument filter = new BsonDocument("_id", MANIFEST_ID);
		LOGGER.debug("| Initial manifest: {}", doc);
		ReplaceOptions options = new ReplaceOptions().upsert(true);
		UpdateResult result = collection.replaceOne(filter, doc, options);
		LOGGER.debug("| Manifest result: {}", result);
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection}.
	 */
	@Override
	public void onEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		assert event.getDocumentKey() != null;
		if (MANIFEST_ID.equals(event.getDocumentKey().get("_id"))) {
			onManifestEvent(event);
			return;
		}
		if (!DOCUMENT_FILTER.equals(event.getDocumentKey())) {
			LOGGER.debug("Ignoring event for unrecognized document key: {}", event.getDocumentKey());
			return;
		}
		switch (event.getOperationType()) {
			case INSERT: case REPLACE: {
				// Note: an INSERT could be coming from this very bosk initializing the collection,
				// in which case replacing the entire bosk state downstream is unnecessary.
				// However, it could also be coming from another bosk, or even a human operator doing
				// a repair, so we can't ignore it either.
				// It seems unavoidable to pass the change downstream.
				BsonDocument fullDocument = event.getFullDocument();
				if (fullDocument == null) {
					// The MongoDB docs are confusing, but it seems there should
					// always be a fullDocument for INSERT events, and probably
					// also REPLACE. That would imply that this case is impossible.
					throw new UnprocessableEventException("Missing fullDocument", event.getOperationType());
				}
				BsonInt64 revision = formatter.getRevisionFromFullDocument(fullDocument);
				BsonDocument state = fullDocument.getDocument(DocumentFields.state.name(), null);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", event.getOperationType());
				}
				R newRoot = formatter.document2object(state, rootRef);
				// Note that we do not check revisionToSkip here. We probably should... but this actually
				// saves us in MongoDriverResiliencyTest.documentReappears_recovers because when the doc
				// disappears, we don't null out revisionToSkip. TODO: Rethink what's the right way to handle this.
				LOGGER.debug("| Replace {}", rootRef);
				MapValue<String> diagnosticAttributes = formatter.eventDiagnosticAttributesFromFullDocument(fullDocument);
				try (var __ = rootRef.diagnosticContext().withOnly(diagnosticAttributes)) {
					downstream.submitReplacement(rootRef, newRoot);
				}
				flushLock.finishedRevision(revision);
			} break;
			case UPDATE: {
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					BsonInt64 revision = formatter.getRevisionFromUpdateEvent(event);
					if (shouldNotSkip(revision)) {
						MapValue<String> diagnosticAttributes = formatter.eventDiagnosticAttributesFromUpdate(event);
						try (var __ = rootRef.diagnosticContext().withOnly(diagnosticAttributes)) {
							replaceUpdatedFields(updateDescription.getUpdatedFields());
							deleteRemovedFields(updateDescription.getRemovedFields(), event.getOperationType());
						}
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
	private void onManifestEvent(ChangeStreamDocument<BsonDocument> event) throws UnprocessableEventException {
		LOGGER.debug("onManifestEvent({})", event.getOperationType().name());
		if (event.getOperationType() == INSERT || event.getOperationType() == REPLACE) {
			BsonDocument manifest = requireNonNull(event.getFullDocument());
			manifest.remove("_id");
			try {
				formatter.validateManifest(manifest);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!new BsonDocument().equals(manifest.get("sequoia"))) {
				throw new UnprocessableEventException("Unexpected value in manifest \"sequoia\" field: " + manifest.get("sequoia"), event.getOperationType());
			}
		} else {
			// SequoiaFormatDriver always uses INSERT/REPLACE to update the manifest;
			// anything else is unexpected.
			throw new UnprocessableEventException("Unexpected change to manifest document", event.getOperationType());
		}
		LOGGER.debug("Ignoring benign manifest change event");
	}

	@Override
	public void onRevisionToSkip(BsonInt64 revision) {
		LOGGER.debug("+ onRevisionToSkip({})", revision.longValue());
		revisionToSkip = revision;
		flushLock.finishedRevision(revision);
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
			try (MongoCursor<BsonDocument> cursor = collection
				.withReadConcern(LOCAL) // The revision field needs to be the latest
				.find(DOCUMENT_FILTER)
				.limit(1)
				.projection(fields(include(DocumentFields.revision.name())))
				.cursor()
			) {
				BsonDocument doc = cursor.next();
				BsonInt64 result = doc.getInt64(DocumentFields.revision.name(), null);
				if (result == null) {
					// Document exists but has no revision field.
					// In that case, newer servers (including this one) will create the
					// the field upon initialization, and we're ok to wait for any old
					// revision number at all.
					LOGGER.debug("No revision field; assuming {}", REVISION_ZERO.longValue());
					return REVISION_ZERO;
				} else {
					LOGGER.debug("Read revision {}", result.longValue());
					return result;
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

	private BsonDocument documentFilter() {
		return new BsonDocument("_id", DOCUMENT_ID);
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target) {
		BsonDocument filter = documentFilter();
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

	private <T> BsonDocument replacementDoc(Reference<T> target, T newValue) {
		String key = dottedFieldNameOf(target, rootRef);
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		LOGGER.debug("| Set field {}: {}", key, value);
		BsonDocument result = updateDoc();
		result.compute("$set", (__,existing) -> {
			if (existing == null) {
				return new BsonDocument(key, value);
			} else {
				return existing.asDocument().append(key, value);
			}
		});
		return result;
	}

	private <T> BsonDocument deletionDoc(Reference<T> target) {
		String key = dottedFieldNameOf(target, rootRef);
		LOGGER.debug("| Unset field {}", key);
		return updateDoc().append("$unset", new BsonDocument(key, new BsonNull())); // Value is ignored
	}

	private BsonDocument updateDoc() {
		return new BsonDocument("$inc", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(1)))
			.append("$set", new BsonDocument(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(rootRef.diagnosticContext().getAttributes())));
	}

	private BsonDocument initialDocument(BsonValue initialState, BsonInt64 revision) {
		BsonDocument fieldValues = new BsonDocument("_id", DOCUMENT_ID);

		fieldValues.put(DocumentFields.path.name(), new BsonString("/"));
		fieldValues.put(DocumentFields.state.name(), initialState);
		fieldValues.put(DocumentFields.revision.name(), revision);
		fieldValues.put(DocumentFields.diagnostics.name(), formatter.encodeDiagnostics(rootRef.diagnosticContext().getAttributes()));

		return fieldValues;
	}

	/**
	 * @return true if something changed
	 */
	private boolean doUpdate(BsonDocument updateDoc, BsonDocument filter) {
		LOGGER.debug("| Update: {}", updateDoc);
		LOGGER.debug("| Filter: {}", filter);
		UpdateResult result = collection.updateOne(filter, updateDoc);
		LOGGER.debug("| Update result: {}", result);
		if (result.wasAcknowledged()) {
			// NOTE: This case can occur in a few situations:
			// 1. A conditional update whose precondition failed
			// 2. An update inside a nonexistent node
			// 3. The bosk document has disappeared
			//
			// Differentiating these cases without transactions is complex,
			// and the only benefit of the Sequoia format is its simplicity,
			// so for now, we're opting not to handle this case.
			//
			// This means valid updates can be silently ignored during the window
			// between when a refurbish operation deletes the bosk document and
			// when the corresponding change event arrives. We are going to accept
			// and document this risk for the time being, unless we can determine
			// a sufficiently straightforward way to detect this situation.
			//
			// Therefore, when refurbishing from Sequoia to another format,
			// the system should be quiescent or else updates may be lost.

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
	private static final BsonDocument DOCUMENT_FILTER = new BsonDocument("_id", DOCUMENT_ID);
	private static final Logger LOGGER = LoggerFactory.getLogger(SequoiaFormatDriver.class);
}
