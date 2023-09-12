package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.OperationType;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.lang.Nullable;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.RootReference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.BsonSurgeon.GraftPoint;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.var;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mongodb.ReadConcern.LOCAL;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.fields;
import static com.mongodb.client.model.Projections.include;
import static com.mongodb.client.model.changestream.OperationType.DELETE;
import static com.mongodb.client.model.changestream.OperationType.INSERT;
import static io.vena.bosk.Path.parseParameterized;
import static io.vena.bosk.drivers.mongo.BsonSurgeon.docSegments;
import static io.vena.bosk.drivers.mongo.Formatter.REVISION_ZERO;
import static io.vena.bosk.drivers.mongo.Formatter.dottedFieldNameOf;
import static io.vena.bosk.drivers.mongo.Formatter.enclosingReference;
import static io.vena.bosk.drivers.mongo.MainDriver.MANIFEST_ID;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode.CREATE_IF_ABSENT;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode.HASTY;
import static io.vena.bosk.util.Classes.enumerableByIdentifier;
import static java.util.Collections.newSetFromMap;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.EnumSet.complementOf;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;

/**
 * A {@link FormatDriver} that stores the entire bosk state in a single document.
 */
final class PandoFormatDriver<R extends StateTreeNode> implements FormatDriver<R> {
	private final String description;
	private final PandoFormat format;
	private final MongoDriverSettings settings;
	private final Formatter formatter;
	private final TransactionalCollection<Document> collection;
	private final RootReference<R> rootRef;
	private final BoskDriver<R> downstream;
	private final FlushLock flushLock;
	private final BsonSurgeon bsonSurgeon;
	private final Demultiplexer demultiplexer = new Demultiplexer();

	private volatile BsonInt64 revisionToSkip = null;

	static final BsonString ROOT_DOCUMENT_ID = new BsonString("|");

	PandoFormatDriver(
		Bosk<R> bosk,
		MongoCollection<Document> collection,
		MongoDriverSettings driverSettings,
		PandoFormat format, BsonPlugin bsonPlugin,
		MongoClient mongoClient,
		FlushLock flushLock,
		BoskDriver<R> downstream
	) {
		this.description = PandoFormatDriver.class.getSimpleName() + ": " + driverSettings;
		this.settings = driverSettings;
		this.format = format;
		this.formatter = new Formatter(bosk, bsonPlugin);
		this.collection = TransactionalCollection.of(collection, mongoClient);
		this.rootRef = bosk.rootReference();
		this.downstream = downstream;
		this.flushLock = flushLock;

		this.bsonSurgeon = new BsonSurgeon(
			format.separateCollections().stream()
			.map(s -> referenceTo(s, rootRef))
			.sorted(comparing((Reference<?> ref) -> ref.path().length()).reversed())
			.collect(toList()));
	}

	private static Reference<EnumerableByIdentifier<Entity>> referenceTo(String pathString, RootReference<?> rootRef) {
		try {
			return rootRef.then(enumerableByIdentifier(Entity.class), parseParameterized(pathString));
		} catch (InvalidTypeException e) {
			throw new IllegalArgumentException("Invalid configuration -- path does not point to a Catalog or SideTable: " + pathString, e);
		}
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		try (var txn = collection.newTransaction()) {
			doReplacement(target, newValue, txn);
		}
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		try (var txn = collection.newTransaction()) {
			if (documentExists(documentFilter(mainRef(target)))) {
				return;
			}
			doReplacement(target, newValue, txn);
		}
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		try (var txn = collection.newTransaction()) {
			doDelete(target, txn);
		}
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var txn = collection.newTransaction()) {
			if (preconditionFailed(precondition, requiredValue)) {
				return;
			}
			doReplacement(target, newValue, txn);
		}
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		try (var txn = collection.newTransaction()) {
			if (preconditionFailed(precondition, requiredValue)) {
				return;
			}
			doDelete(target, txn);
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
		List<Document> allParts = new ArrayList<>();
		try (MongoCursor<Document> cursor = collection
			.withReadConcern(LOCAL) // The revision field needs to be the latest
			.find(regex("_id", "^" + Pattern.quote("|")))
			.cursor()
		) {
			while (cursor.hasNext()) {
				allParts.add(cursor.next());
			}
		} catch (NoSuchElementException e) {
			throw new UninitializedCollectionException("No existing document", e);
		}
		Document mainPart = allParts.get(allParts.size()-1);
		Long revision = mainPart.get(DocumentFields.revision.name(), 0L);
		List<BsonDocument> partsList = allParts
			.stream()
			.map(d -> d.toBsonDocument(BsonDocument.class, formatter.codecRegistry()))
			.collect(toList());

		BsonDocument combinedState = bsonSurgeon.gather(partsList);
		R root = formatter.document2object(combinedState, rootRef);
		BsonInt64 rev = new BsonInt64(revision);
		return new StateAndMetadata<>(root, rev);
	}

	@Override
	public void initializeCollection(StateAndMetadata<R> priorContents) {
		BsonValue initialState = formatter.object2bsonValue(priorContents.state, rootRef.targetType());
		BsonInt64 newRevision = new BsonInt64(1 + priorContents.revision.longValue());

		try (var txn = collection.newTransaction()) {
			LOGGER.debug("** Initial upsert for {}", ROOT_DOCUMENT_ID.getValue());
			if (initialState instanceof BsonDocument) {
				upsertAndRemoveSubParts(rootRef, initialState.asDocument()); // Mutates initialState!
			}
			BsonDocument update = new BsonDocument("$set", initialDocument(initialState, newRevision));
			BsonDocument filter = rootDocumentFilter();
			UpdateOptions options = new UpdateOptions().upsert(true);
			LOGGER.trace("| Filter: {}", filter);
			LOGGER.trace("| Update: {}", update);
			LOGGER.trace("| Options: {}", options);
			UpdateResult result = collection.updateOne(filter, update, options);
			LOGGER.debug("| Result: {}", result);
			if (settings.experimental().manifestMode() == CREATE_IF_ABSENT) {
				writeManifest();
			}
			txn.commit();
		}
	}

	private void writeManifest() {
		BsonDocument doc = new BsonDocument("_id", MANIFEST_ID);
		doc.putAll((BsonDocument) formatter.object2bsonValue(Manifest.forPando(format), Manifest.class));
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
		if (event.getDocumentKey() == null) {
			throw new UnprocessableEventException("Null document key", event.getOperationType());
		}
		BsonValue bsonDocumentID = event.getDocumentKey().get("_id");
		if (!(bsonDocumentID instanceof BsonString)) {
			LOGGER.debug("Ignoring event for unrecognized non-string document key: {}", event.getDocumentKey());
			return;
		}
		if (MANIFEST_ID.equals(bsonDocumentID)) {
			onManifestEvent(event);
			return;
		}

		// This is an event we care about

		if (event.getTxnNumber() == null) {
			LOGGER.debug("Processing standalone event {} on {}", event.getOperationType(), event.getDocumentKey());
			processTransaction(singletonList(event));
		} else {
			demultiplexer.add(event);
			if (ROOT_DOCUMENT_ID.equals(bsonDocumentID)) {
				LOGGER.debug("Processing final event {} on {}", event.getOperationType(), event.getDocumentKey());
				processTransaction(demultiplexer.pop(event));
			} else {
				LOGGER.debug("Queueing transaction event {} on {}", event.getOperationType(), event.getDocumentKey());
			}
		}
	}

	private void processTransaction(List<ChangeStreamDocument<Document>> events) throws UnprocessableEventException {
		ChangeStreamDocument<Document> finalEvent = events.get(events.size() - 1);
		switch (finalEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				Document fullDocument = finalEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on final event", finalEvent.getOperationType());
				}

				BsonInt64 revision = getRevisionFromFullDocumentEvent(fullDocument);
				if (shouldSkip(revision)) {
					LOGGER.debug("Skipping revision {}", revision.longValue());
					return;
				}

				Document state = fullDocument.get(DocumentFields.state.name(), Document.class);
				if (state == null) {
					ChangeStreamDocument<Document> mainEvent = events.get(events.size() - 2);
					LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
					propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
				} else {
					LOGGER.debug("Main event is final event");
					propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
				}

				flushLock.finishedRevision(revision);
			} break;
			case UPDATE: {
				// TODO: Combine code with INSERT and REPLACE events
				BsonInt64 revision = getRevisionFromUpdateEvent(finalEvent);
				boolean mainEventIsFinalEvent = updateEventHasStateField(finalEvent);
				if (mainEventIsFinalEvent) {
					LOGGER.debug("Main event is final event");
					propagateDownstream(finalEvent, events.subList(0, events.size() - 1));
				} else {
					ChangeStreamDocument<Document> mainEvent = events.get(events.size() - 2);
					assert updateEventHasStateField(mainEvent);
					LOGGER.debug("Main event is {} on {}", mainEvent.getOperationType(), mainEvent.getDocumentKey());
					propagateDownstream(mainEvent, events.subList(0, events.size() - 2));
				}
				flushLock.finishedRevision(revision);
			} break;
			case DELETE: {
				// No other events in the transaction matter if the root document is gone
				LOGGER.debug("Document containing revision field has been deleted; assuming revision=0");
				flushLock.finishedRevision(REVISION_ZERO);
			} break;
		}
	}

	private void propagateDownstream(ChangeStreamDocument<Document> mainEvent, List<ChangeStreamDocument<Document>> priorEvents) throws UnprocessableEventException {
		switch (mainEvent.getOperationType()) {
			case INSERT: case REPLACE: {
				Document fullDocument = mainEvent.getFullDocument();
				if (fullDocument == null) {
					throw new UnprocessableEventException("Missing fullDocument on main event", mainEvent.getOperationType());
				}

				Document state = fullDocument.get(DocumentFields.state.name(), Document.class);
				if (state == null) {
					throw new UnprocessableEventException("Missing state field", mainEvent.getOperationType());
				}

				Reference<?> mainRef;
				BsonDocument bsonState;
				if (priorEvents == null) {
					LOGGER.debug("No prior events");
					bsonState = formatter.document2BsonDocument(state);
					mainRef = documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent);
				} else {
					LOGGER.debug("{} prior events", priorEvents.size());
					List<BsonDocument> parts = subpartDocuments(priorEvents);
					parts.add(formatter.document2BsonDocument(fullDocument));
					bsonState = bsonSurgeon.gather(parts);
					mainRef = documentID2MainRef(fullDocument.getString("_id"), mainEvent);
				}

				LOGGER.debug("| Replace {}", mainRef);
				submitReplacementDownstream(mainRef, bsonState);
			} break;
			case UPDATE: {
				UpdateDescription updateDescription = mainEvent.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(updateDescription.getUpdatedFields(), subpartDocuments(priorEvents));
					deleteRemovedFields(updateDescription.getRemovedFields(), mainEvent.getOperationType());
				}
			} break;
			case DELETE: {
				// No other events in the transaction matter if the main document is deleted
				Reference<?> mainRef = mainRef(documentID2MainRef(mainEvent.getDocumentKey().getString("_id").getValue(), mainEvent));
				LOGGER.debug("| Replace {}", mainRef);
				downstream.submitDeletion(mainRef);
			} break;
			default: {
				throw new UnprocessableEventException("Cannot process event", mainEvent.getOperationType());
			}
		}
	}

	private List<BsonDocument> subpartDocuments(List<ChangeStreamDocument<Document>> priorEvents) {
		return priorEvents.stream()
			.filter(e -> OPERATIONS_TO_INCLUDE_IN_GATHER.contains(e.getOperationType()))
			.map(this::fullDocumentForSubPart)
			.map(formatter::document2BsonDocument)
			.collect(toCollection(ArrayList::new));
	}

	private @NonNull Document fullDocumentForSubPart(ChangeStreamDocument<Document> event) {
		Document result = event.getFullDocument();
		if (result == null) {
			throw new IllegalStateException("No full document in change stream event for subpart: " + event.getOperationType() + " on " + event.getDocumentKey());
		}
		return result;
	}

	/**
	 * This lets us use Java generics to avoid some ugly typecasts
	 */
	private <T> void submitReplacementDownstream(Reference<T> mainRef, BsonDocument bsonState) {
		T newValue = formatter.document2object(bsonState, mainRef);
		LOGGER.debug("| Replace {}", mainRef);
		downstream.submitReplacement(mainRef, newValue);
	}

	private Reference<?> documentID2MainRef(String pipedPath, ChangeStreamDocument<Document> event) throws UnprocessableEventException {
		// referenceTo does everything we need already. Build a fake dotted field name and use that
		String dottedName = "state" + pipedPath.replace('|', '.');
		try {
			return Formatter.referenceTo(dottedName, rootRef);
		} catch (InvalidTypeException e) {
			throw new UnprocessableEventException("Invalid path from document ID: \"" + pipedPath + "\"", e, event.getOperationType());
		}
	}

	/**
	 * We're required to cope with anything we might ourselves do in {@link #initializeCollection},
	 * but outside that, we want to be as strict as possible
	 * so incompatible database changes don't go unnoticed.
	 */
	private void onManifestEvent(ChangeStreamDocument<Document> event) throws UnprocessableEventException {
		if (event.getOperationType() == INSERT) {
			Document manifestDoc = requireNonNull(event.getFullDocument());
			Manifest manifest;
			try {
				manifest = formatter.decodeManifest(manifestDoc);
			} catch (UnrecognizedFormatException e) {
				throw new UnprocessableEventException("Invalid manifest", e, event.getOperationType());
			}
			if (!manifest.equals(Manifest.forPando(format))) {
				throw new UnprocessableEventException("Manifest indicates format has changed", event.getOperationType());
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

	private static boolean updateEventHasStateField(ChangeStreamDocument<Document> event) {
		if (event == null) {
			return false;
		}
		UpdateDescription updateDescription = event.getUpdateDescription();
		if (updateDescription == null) {
			return false;
		}
		BsonDocument updatedFields = updateDescription.getUpdatedFields();
		if (updatedFields == null) {
			return false;
		}

		return updatedFields.keySet().stream().anyMatch(k -> k.startsWith(DocumentFields.state.name()));
	}

	//
	// MongoDB helpers
	//

	private <T> void doReplacement(Reference<T> target, T newValue, TransactionalCollection<Document>.Transaction txn) {
		BsonDocument rootUpdate;
		Reference<?> mainRef = mainRef(target);
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		if (value instanceof BsonDocument) {
			deleteParts(mainRef);
			BsonDocument mainPart = upsertAndRemoveSubParts(target, value.asDocument());
			if (rootRef.equals(mainRef)) {
				rootUpdate = replacementDoc(target, value, rootRef);
			} else {
				// Note: don't use mainPart's ID. TODO: Is this ok? Why is the ID wrong?
				BsonDocument filter = new BsonDocument("_id", documentFilter(mainRef));
				if (target.equals(mainRef)) {
					// Upsert the main doc
					// TODO: merge this with the same code in upsertAndRemoveSubParts
					BsonDocument update = new BsonDocument("$set", mainPart);
					collection.updateOne(filter, update, new UpdateOptions().upsert(true));
				} else {
					// Update part of the main doc (which must already exist)
					String key = dottedFieldNameOf(target, mainRef);
					LOGGER.debug("| Set field {} in {}: {}", key, mainRef, value);
					BsonDocument mainUpdate = new BsonDocument("$set", new BsonDocument(key, value));
					doUpdate(mainUpdate, standardPreconditions(target, mainRef, filter));
				}
				// On the root doc, we're only bumping the revision
				rootUpdate = blankUpdateDoc();
			}
		} else {
			rootUpdate = replacementDoc(target, value, rootRef);
		}
		doUpdate(rootUpdate, standardRootPreconditions(target));
		txn.commit();
	}

	private <T> void doDelete(Reference<T> target, TransactionalCollection<Document>.Transaction txn) {
		deleteParts(mainRef(target));
		if (doUpdate(deletionDoc(target, rootRef), standardRootPreconditions(target))) {
			txn.commit();
		} else {
			txn.abort();
		}
	}

	private boolean preconditionFailed(Reference<Identifier> precondition, Identifier requiredValue) {
		Reference<?> mainRef = mainRef(precondition);
		BsonDocument filter = new BsonDocument()
			.append("_id", documentFilter(mainRef))
			.append(dottedFieldNameOf(precondition, mainRef), new BsonString(requiredValue.toString()));
		return !documentExists(filter);
	}

	private boolean documentExists(BsonDocument filter) {
		return 0 != collection.countDocuments(filter, new CountOptions().limit(1));
	}

	/**
	 * @return {@link Reference} to the bosk object corresponding to the document
	 * that contains the given <code>target</code>.
	 */
	private Reference<?> mainRef(Reference<?> target) {
		if (target.path().isEmpty()) {
			return rootRef;
		}

		// The main reference is the "deepest" one that matches the target reference.
		// separateCollections is in descending order of depth.
		// TODO: This could be done more efficiently, perhaps using a trie
		int targetPathLength = target.path().length();
		for (GraftPoint graftPoint: bsonSurgeon.graftPoints) {
			Reference<?> candidate = graftPoint.entryRef();
			if (candidate.path().length() <= targetPathLength) {
				Path portionToMatch = target.path().truncatedTo(candidate.path().length());
				if (candidate.path().matches(portionToMatch)) {
					return candidate.boundBy(portionToMatch);
				}
			}
		}
		return rootRef;
	}

	/**
	 * @return Non-null revision number as per the database.
	 * If the database contains no revision number, returns {@link Formatter#REVISION_ZERO}.
	 */
	private BsonInt64 readRevisionNumber() throws FlushFailureException {
		LOGGER.debug("readRevisionNumber");
		try {
			try (MongoCursor<Document> cursor = collection
				.withReadConcern(LOCAL) // The revision field needs to be the latest
				.find(ROOT_DOCUMENT_FILTER)
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

	private BsonDocument documentFilter(Reference<?> docRef) {
		String id = '|' + String.join("|", docSegments(rootRef, docRef));
		return new BsonDocument("_id", new BsonString(id));
	}

	private <T> BsonDocument standardRootPreconditions(Reference<T> target) {
		return standardPreconditions(target, rootRef, rootDocumentFilter());
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target, Reference<?> startingRef, BsonDocument filter) {
		if (!target.path().equals(startingRef.path())) {
			String enclosingObjectKey = dottedFieldNameOf(enclosingReference(target), startingRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
	}

	private <T> BsonDocument explicitPreconditions(Reference<T> target, Reference<Identifier> preconditionRef, Identifier requiredValue) {
		BsonDocument filter = standardRootPreconditions(target);
		BsonDocument precondition = new BsonDocument("$eq", new BsonString(requiredValue.toString()));
		filter.put(dottedFieldNameOf(preconditionRef, rootRef), precondition);
		return filter;
	}

	private <T> BsonDocument replacementDoc(Reference<T> target, BsonValue value, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Set field {}: {}", key, value);
		return blankUpdateDoc()
			.append("$set", new BsonDocument(key, value));
	}

	private <T> BsonDocument deletionDoc(Reference<T> target, Reference<?> startingRef) {
		String key = dottedFieldNameOf(target, startingRef);
		LOGGER.debug("| Unset field {}", key);
		return blankUpdateDoc().append("$unset", new BsonDocument(key, new BsonNull())); // Value is ignored
	}

	private BsonDocument blankUpdateDoc() {
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
	private void replaceUpdatedFields(@Nullable BsonDocument updatedFields, List<BsonDocument> subParts) {
		if (updatedFields != null) {
			boolean alreadyUsedSubparts = false;
			for (Map.Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(DocumentFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = Formatter.referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						logNonexistentField(dottedName, e);
						continue;
					}
					LOGGER.debug("| Replace {}", ref);

					if (alreadyUsedSubparts) {
						throw new IllegalStateException("Not expecting an update event that changes multiple state fields");
					} else {
						alreadyUsedSubparts = true;
					}
					String mainID = "|" + String.join("|", BsonSurgeon.docSegments(rootRef, ref));
					BsonDocument mainDocument = new BsonDocument()
						.append("_id", new BsonString(mainID))
						.append("state", entry.getValue());
					ArrayList<BsonDocument> parts = new ArrayList<>(subParts.size() + 1);
					parts.addAll(subParts);
					parts.add(mainDocument);

					BsonValue value = bsonSurgeon.gather(parts);
					Object replacement = formatter.bsonValue2object(value, ref);
					downstream.submitReplacement(ref, replacement);
				}
			}
		}
	}

	private boolean shouldSkip(BsonInt64 revision) {
		return revision != null && revisionToSkip != null && revision.longValue() <= revisionToSkip.longValue();
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
						ref = Formatter.referenceTo(dottedName, rootRef);
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

	private <T> void deleteParts(Reference<T> mainRef) {
		if (settings.experimental().orphanDocumentMode() == HASTY) {
			LOGGER.debug("Skipping deleteParts({}) in {} mode", mainRef, HASTY);
		} else {
			String prefix;
			if (mainRef.path().isEmpty()) {
				prefix = "|";
			} else {
				prefix = "|" + String.join("|", docSegments(rootRef, mainRef)) + "|";
			}

			// Every doc whose ID starts with the prefix and has at least one more character
			Bson filter = regex("_id", "^" + Pattern.quote(prefix) + ".");

			DeleteResult result = collection.deleteMany(filter);
			LOGGER.debug("deleteParts({}) result: {} filter: {}", mainRef, result, filter);
		}
	}

	/**
	 * @param value is mutated to stub-out the parts written to the database
	 * @return the <em>main part</em> document, representing the root of the tree of part-documents
	 * (which is not the root of the bosk state tree, unless of course <code>target</code> is the root reference)
	 */
	private <T> BsonDocument upsertAndRemoveSubParts(Reference<T> target, BsonDocument value) {
		List<BsonDocument> allParts = bsonSurgeon.scatter(rootRef, target, value);
		// NOTE: `value` has now been mutated so the parts have been stubbed out

		List<BsonDocument> subParts = allParts.subList(0, allParts.size() - 1);

		UpdateOptions options = new UpdateOptions().upsert(true);
		LOGGER.debug("Document has {} sub-parts", subParts.size());
		for (BsonDocument part: subParts) {
			BsonDocument update = new BsonDocument("$set", part);
			BsonDocument filter = new BsonDocument("_id", part.get("_id"));
			LOGGER.debug("Upsert sub-part: filter={} update={}", filter, update);
			UpdateResult result = collection.updateOne(filter, update, options);
			LOGGER.debug("| Update result: {}", result);
		}

		return allParts.get(allParts.size()-1);
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
	private static final BsonDocument ROOT_DOCUMENT_FILTER = new BsonDocument("_id", ROOT_DOCUMENT_ID);
	private static final EnumSet<OperationType> OPERATIONS_TO_INCLUDE_IN_GATHER = complementOf(EnumSet.of(DELETE));
	private static final Logger LOGGER = LoggerFactory.getLogger(PandoFormatDriver.class);
}
