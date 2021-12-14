package org.vena.bosk.drivers;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.UpdateDescription;
import com.mongodb.client.result.UpdateResult;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.Value;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWriter;
import org.bson.BsonNull;
import org.bson.BsonReader;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.DocumentCodecProvider;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.ValueCodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.BsonPlugin;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.Mapping;
import org.vena.bosk.Reference;
import org.vena.bosk.SerializationPlugin;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static com.mongodb.ErrorCategory.DUPLICATE_KEY;
import static java.lang.String.format;
import static java.lang.Thread.currentThread;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.bson.BsonBoolean.FALSE;
import static org.vena.bosk.ReferenceUtils.rawClass;

public final class MongoDriver<R extends Entity> implements BoskDriver<R> {
	private final BoskDriver<R> downstream;
	private final MongoCollection<Document> collection;
	private final BsonString tenantID;
	private final Reference<R> rootRef;
	private final CodecRegistry simpleCodecs;
	private final Function<Type, Codec<?>> preferredBoskCodecs;
	private final Function<Reference<?>, SerializationPlugin.DeserializationScope> deserializationScopeFunction;
	private final ExecutorService ex = Executors.newFixedThreadPool(1);
	private final ConcurrentHashMap<String, BlockingQueue<BsonDocument>> echoListeners = new ConcurrentHashMap<>();
	private final String echoPrefix;
	private final AtomicLong echoCounter = new AtomicLong(1_000_000_000_000L); // Start with a big number so the length doesn't change often
	private final static String RESUME_TOKEN_KEY = "_data";
	private final MongoCursor<ChangeStreamDocument<Document>> eventCursor;
	private volatile BsonDocument lastProcessedResumeToken;

	public MongoDriver(BoskDriver<R> downstream, Bosk<R> bosk, MongoCollection<Document> collection, Identifier tenantID, BsonPlugin bsonPlugin) {
		this.downstream = downstream;
		this.collection = collection;
		this.echoPrefix = bosk.instanceID().toString();
		this.tenantID = new BsonString(tenantID.toString());
		this.rootRef = bosk.rootReference();

		this.simpleCodecs = CodecRegistries.fromProviders(bsonPlugin.codecProviderFor(bosk), new ValueCodecProvider(), new DocumentCodecProvider());
		this.preferredBoskCodecs = type -> bsonPlugin.getCodec(type, rawClass(type), simpleCodecs, bosk);
		this.deserializationScopeFunction = bsonPlugin::newDeserializationScope;
		this.lastProcessedResumeToken = new BsonDocument();
		lastProcessedResumeToken.put(RESUME_TOKEN_KEY, new BsonString("0"));

		eventCursor = collection.watch().iterator();
		initiateEventProcessing();
	}

	//
	// MongoDB initialization
	//

	private void ensureTenantDocumentExists(BsonValue initialState) {
		BsonDocument filter = tenantFilter();
		BsonDocument update = initialTenantUpsert(initialState);
		UpdateOptions options = new UpdateOptions();
		options.upsert(true);
		LOGGER.debug("** Initial tenant upsert for {}", tenantID);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result = null;
		try {
			result = collection.updateOne(filter, update, options);
		} catch (MongoWriteException e) {
			if (DUPLICATE_KEY == ErrorCategory.fromErrorCode(e.getCode())) {
				// This can happen in MongoDB 4.0 if two upserts occur in parallel.
				// https://docs.mongodb.com/v4.0/reference/method/db.collection.update/
				LOGGER.debug("| Retrying: {}", e.getMessage());
				result = collection.updateOne(filter, update, options);
			} else {
				throw e;
			}
		}
		LOGGER.debug("| Result: {}", result);
	}

	BsonDocument initialTenantUpsert(BsonValue initialState) {
		BsonDocument fieldValues = new BsonDocument("_id", tenantID);
		fieldValues.put(TenantFields.state.name(), initialState);
		fieldValues.put(TenantFields.echo.name(), new BsonString(uniqueEchoToken()));
		return new BsonDocument("$setOnInsert", fieldValues);
	}

	//
	// Change stream event processing from MongoDB
	//

	private void initiateEventProcessing() {
		ex.submit(() -> {
			String oldName = currentThread().getName();
			currentThread().setName(getClass().getSimpleName() + "-events");
			try {
				while (!ex.isShutdown()) {
					LOGGER.debug("- Awaiting event");
					ChangeStreamDocument<Document> event = eventCursor.next();
					try {
						processEvent(event);
					} catch (Throwable e) {
						LOGGER.error("Unable to process event: " + event, e);
						// TODO: How to handle this? For now, just keep soldiering on
					}
				}
			} finally {
				LOGGER.warn("Terminating MongoDriver event processing thread {}", currentThread().getName());
				currentThread().setName(oldName);
			}
		});
	}

	/**
	 * Attempts a clean shutdown of the resources used by this driver.
	 * In typical usage, a Bosk remains in use for the entire duration of
	 * the Java process, so there's no need for orderly shutdown.
	 * This method is offered on a best-effort basis for situations,
	 * such as tests, in which a clean shutdown is desired but not critical.
	 */
	public void close() {
		eventCursor.close();
		ex.shutdown();
		try {
			boolean success = ex.awaitTermination(10, SECONDS);
			if (!success) {
				LOGGER.warn("Timeout during shutdown");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOGGER.warn("Interrupted during shutdown", e);
		}
	}

	private void processEvent(ChangeStreamDocument<Document> event) {
		LOGGER.debug("# EVENT: {}", event);
		switch (event.getOperationType()) {
			case INSERT: case REPLACE:// Both of these represent replacing the whole tenant document
				// getFullDocument is reliable for INSERT and REPLACE operations:
				//   https://docs.mongodb.com/v4.0/reference/change-events/#change-stream-output
				LOGGER.debug("| Replace tenant - IGNORE");
				//driver.submitReplacement(rootRef, document2object(event.getFullDocument().get(TenantFields.root), rootRef));
				// TODO
				break;
			case UPDATE:
				UpdateDescription updateDescription = event.getUpdateDescription();
				if (updateDescription != null) {
					replaceUpdatedFields(updateDescription.getUpdatedFields());
					deleteRemovedFields(updateDescription.getRemovedFields());
					notifyIfEcho(updateDescription.getUpdatedFields(), event.getResumeToken());
				}
				break;
			default:
				throw new NotYetImplementedException("Unknown change stream event: " + event);
		}
		lastProcessedResumeToken = event.getResumeToken();
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitReplacement submitReplacement}</code>
	 * for each updated field.
	 */
	private void replaceUpdatedFields(@Nullable BsonDocument updatedFields) {
		if (updatedFields != null) {
			for (Entry<String, BsonValue> entry : updatedFields.entrySet()) {
				String dottedName = entry.getKey();
				if (dottedName.startsWith(TenantFields.state.name())) {
					Reference<Object> ref;
					try {
						ref = referenceTo(dottedName, rootRef);
					} catch (InvalidTypeException e) {
						throw new IllegalStateException("Update of invalid field \"" + dottedName + "\"", e);
					}
					LOGGER.debug("| Replace {}", ref);
					Object replacement = bsonValue2object(entry.getValue(), ref);
					downstream.submitReplacement(ref, replacement);
				}
			}
		}
	}

	/**
	 * Call <code>downstream.{@link BoskDriver#submitDeletion submitDeletion}</code>
	 * for each removed field.
	 */
	private void deleteRemovedFields(@Nullable List<String> removedFields) {
		if (removedFields != null) {
			for (String dottedName : removedFields) {
				Reference<Object> ref;
				try {
					ref = referenceTo(dottedName, rootRef);
				} catch (InvalidTypeException e) {
					throw new IllegalStateException("Delete of invalid field \"" + dottedName + "\"", e);
				}
				LOGGER.debug("| Delete {}", ref);
				downstream.submitDeletion(ref);
			}
		}
	}

	private void notifyIfEcho(@Nullable BsonDocument updatedFields, BsonDocument resumeToken) {
		if (updatedFields != null) {
			BsonValue newValue = updatedFields.get(TenantFields.echo.name());
			if (newValue != null) {
				String echoToken = newValue.asString().getValue();
				BlockingQueue<BsonDocument> listener = echoListeners.remove(echoToken);
				if (listener != null) {
					LOGGER.debug("| Echo {}: {}", echoToken, resumeToken);
					listener.add(resumeToken);
				}
			}
		}
	}

	//
	// Sending BoskDriver instructions to MongoDB
	//

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException {
		LOGGER.debug("+ initialRoot");
		try (MongoCursor<Document> cursor = collection.find(tenantFilter()).limit(1).cursor()) {
			Document newDocument = cursor.next();
			Document newState = newDocument.get(TenantFields.state.name(), Document.class);
			if (newState == null) {
				LOGGER.debug("| No existing state; delegating downstream");
			} else {
				LOGGER.debug("| From database: {}", newState);
				return document2object(newState, rootRef);
			}
		} catch (NoSuchElementException e) {
			LOGGER.debug("| No tenant document; delegating downstream");
		}

		R root = downstream.initialRoot(rootType);
		ensureTenantDocumentExists(object2bsonValue(root, rootType));
		return root;
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		LOGGER.debug("+ submitReplacement({})", target);
		doUpdate(replacementDoc(target, newValue), standardPreconditions(target));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		LOGGER.debug("+ submitInitialization({})", target);
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
		LOGGER.debug("+ submitDeletion({})", target);
		if (target.path().isEmpty()) {
			throw new IllegalArgumentException("Can't delete the root of the bosk");
		} else {
			doUpdate(deletionDoc(target), standardPreconditions(target));
		}
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
			assert result.getMatchedCount() <= 1;
			return result.getMatchedCount() >= 1;
		} else {
			LOGGER.error("Mongo write was not acknowledged.\n\tFilter: {}\n\tUpdate: {}\n\tResult: {}", filter, updateDoc, result);
			throw new NotYetImplementedException("Mongo write was not acknowledged");
		}
	}

	@Override
	public void flush() throws InterruptedException {
		LOGGER.debug("+ flush");
		flushToDownstreamDriver();
		downstream.flush();
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<String> precondition, String requiredValue) {
		LOGGER.debug("+ submitConditionalReplacement({}, {} = {})", target, precondition, requiredValue);
		doUpdate(
			replacementDoc(target, newValue),
			explicitPreconditions(target, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<String> precondition, String requiredValue) {
		LOGGER.debug("+ submitConditionalDeletion({}, {} = {})", target, precondition, requiredValue);
		doUpdate(
			deletionDoc(target),
			explicitPreconditions(target, precondition, requiredValue));
	}

	//
	// MongoDB helpers
	//

	private BsonDocument tenantFilter() {
		return new BsonDocument("_id", tenantID);
	}

	private <T> BsonDocument standardPreconditions(Reference<T> target) {
		BsonDocument filter = tenantFilter();
		if (!target.path().isEmpty()) {
			String enclosingObjectKey = dottedFieldNameOf(enclosingReference(target), rootRef);
			BsonDocument condition = new BsonDocument("$type", new BsonString("object"));
			filter.put(enclosingObjectKey, condition);
			LOGGER.debug("| Precondition: {} {}", enclosingObjectKey, condition);
		}
		return filter;
	}

	private <T> BsonDocument explicitPreconditions(Reference<T> target, Reference<String> preconditionRef, String requiredValue) {
		BsonDocument filter = standardPreconditions(target);
		BsonDocument precondition = new BsonDocument("$eq", new BsonString(requiredValue));
		filter.put(dottedFieldNameOf(preconditionRef, rootRef), precondition);
		return filter;
	}

	private <T> BsonDocument replacementDoc(Reference<T> target, T newValue) {
		String key = dottedFieldNameOf(target, rootRef);
		BsonValue value = object2bsonValue(newValue, target.targetType());
		LOGGER.debug("| Set field {}: {}", key, value);
		return new BsonDocument("$set", new BsonDocument(key, value));
	}

	private <T> BsonDocument deletionDoc(Reference<T> target) {
		String key = dottedFieldNameOf(target, rootRef);
		LOGGER.debug("| Unset field {}", key);
		return new BsonDocument("$unset", new BsonDocument(key, new BsonNull())); // Value is ignored
	}

	//
	// Helpers to translate Bosk <-> MongoDB
	//

	@SuppressWarnings("unchecked")
	private <T> T document2object(Document document, Reference<T> target) {
		BsonDocument doc = document.toBsonDocument(BsonDocument.class, simpleCodecs);
		Type type = target.targetType();
		Class<T> objectClass = (Class<T>) rawClass(type);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		try (@SuppressWarnings("unused") BsonPlugin.DeserializationScope scope = deserializationScopeFunction.apply(target)) {
			return objectClass.cast(objectCodec.decode(doc.asBsonReader(), DecoderContext.builder().build()));
		}
	}

	/**
	 * @see #bsonValue2object(BsonValue, Reference)
	 */
	@SuppressWarnings("unchecked")
	private <T> BsonValue object2bsonValue(T object, Type type) {
		rawClass(type).cast(object);
		Codec<T> objectCodec = (Codec<T>) codecFor(type);
		BsonDocument document = new BsonDocument();
		try (BsonDocumentWriter writer = new BsonDocumentWriter(document)) {
			// To support arbitrary values, not just whole documents, we put the result INSIDE a document.
			writer.writeStartDocument();
			writer.writeName("value");
			objectCodec.encode(writer, object, EncoderContext.builder().build());
			writer.writeEndDocument();
		}
		return document.get("value");
	}

	/**
	 * @see #object2bsonValue(Object, Type)
	 */
	@SuppressWarnings("unchecked")
	private <T> T bsonValue2object(BsonValue bson, Reference<T> target) {
		Codec<T> objectCodec = (Codec<T>) codecFor(target.targetType());
		BsonDocument document = new BsonDocument();
		document.append("value", bson);
		try (
			@SuppressWarnings("unused") BsonPlugin.DeserializationScope scope = deserializationScopeFunction.apply(target);
			BsonReader reader = document.asBsonReader()
		) {
			reader.readStartDocument();
			reader.readName("value");
			return objectCodec.decode(reader, DecoderContext.builder().build());
		}
	}

	/**
	 * @return MongoDB field name corresponding to the given Reference
	 * @see #referenceTo(String, Reference)
	 */
	static <T> String dottedFieldNameOf(Reference<T> ref, Reference<?> startingRef) {
		assert startingRef.path().isPrefixOf(ref.path()): "'" + ref + "' must be under '" + startingRef + "'";
		ArrayList<String> segments = new ArrayList<>();
		segments.add(TenantFields.state.name());
		buildDottedFieldNameOf(ref, startingRef.path().length(), segments);
		return String.join(".", segments.toArray(new String[0]));
	}

	private static <T> void buildDottedFieldNameOf(Reference<T> ref, int startingRefLength, ArrayList<String> segments) {
		if (ref.path().length() > startingRefLength) {
			Reference<?> enclosingReference = enclosingReference(ref);
			buildDottedFieldNameOf(enclosingReference, startingRefLength, segments);
			if (Listing.class.isAssignableFrom(enclosingReference.targetClass())) {
				segments.add("ids");
			} else if (Mapping.class.isAssignableFrom(enclosingReference.targetClass())) {
				segments.add("valuesById");
			}
			segments.add(ref.path().lastSegment());
		}
	}

	/**
	 * @return Reference corresponding to the given field name
	 * @see #dottedFieldNameOf(Reference, Reference)
	 */
	@SuppressWarnings("unchecked")
	static <T> Reference<T> referenceTo(String dottedName, Reference<?> startingReference) throws InvalidTypeException {
		Reference<?> ref = startingReference;
		Iterator<String> iter = Arrays.asList(dottedName.split(Pattern.quote("."))).iterator();
		skipField(ref, iter, TenantFields.state.name()); // The entire Bosk state is in this field
		while (iter.hasNext()) {
			if (Listing.class.isAssignableFrom(ref.targetClass())) {
				skipField(ref, iter, "ids");
			} else if (Mapping.class.isAssignableFrom(ref.targetClass())) {
				skipField(ref, iter, "valuesById");
			}
			if (iter.hasNext()) {
				String segment = iter.next();
				ref = ref.then(Object.class, segment);
			}
		}
		return (Reference<T>) ref;
	}

	private static void skipField(Reference<?> ref, Iterator<String> iter, String expectedName) {
		String actualName;
		try {
			actualName = iter.next();
		} catch (NoSuchElementException e) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; encountered end of dotted field name");
		}
		if (!expectedName.equals(actualName)) {
			throw new IllegalStateException("Expected '" + expectedName + "' for " + ref.targetClass().getSimpleName() + "; was: " + actualName);
		}
	}

	/**
	 * If the reference is not a root reference, it always has an enclosing reference
	 * conforming to Object, so this can't throw. Eat the InvalidTypeException.
	 */
	private static <T> Reference<?> enclosingReference(Reference<T> ref) {
		assert !ref.path().isEmpty();
		try {
			return ref.enclosingReference(Object.class);
		} catch (InvalidTypeException e) {
			throw new AssertionError(format("Reference must have an enclosing Object: '%s'", ref), e);
		}
	}

	private Codec<?> codecFor(Type type) {
		// BsonPlugin gives better codecs than CodecRegistry, because BsonPlugin is aware of generics,
		// so we always try that first. The CodecSupplier protocol uses "null" to indicate that another
		// CodecSupplier should be used, so we follow that protocol and fall back on the CodecRegistry.
		// TODO: Should this logic be in BsonPlugin? It has nothing to do with MongoDriver really.
		Codec<?> result = preferredBoskCodecs.apply(type);
		if (result == null) {
			return simpleCodecs.get(rawClass(type));
		} else {
			return result;
		}
	}

	//
	// Echo helper logic
	//

	String uniqueEchoToken() {
		return format("%s_%012d", echoPrefix, echoCounter.addAndGet(1L));
	}

	/**
	 * Ensures that all prior updates have been submitted to the downstream driver.
	 * To do this, we submit a "marker" MongoDB update that doesn't affect the bosk state,
	 * and then wait for that update to arrive back via the change stream.
	 * Because all updates are totally ordered, this means all prior updates have also arrived,
	 * even from other servers; and because our event processing submits them downstream
	 * as they arrive, this means all prior updates are submitted downstream, QED.
	 *
	 * @throws MongoException if something goes wrong with MongoDB
	 */
	private void flushToDownstreamDriver() throws InterruptedException {
		String echoToken = uniqueEchoToken();
		BlockingQueue<BsonDocument> listener = new ArrayBlockingQueue<>(1);
		try {
			echoListeners.put(echoToken, listener);
			BsonDocument updateDoc = new BsonDocument("$set", new BsonDocument(TenantFields.echo.name(), new BsonString(echoToken)));
			LOGGER.debug("| Update: {}", updateDoc);
			collection.updateOne(tenantFilter(), updateDoc);
			LOGGER.debug("| Waiting");
			BsonDocument resumeToken = listener.take();
			MongoResumeTokenSequenceMark sequenceMark = new MongoResumeTokenSequenceMark(resumeToken.getString("_data").getValue());
			LOGGER.debug("| SequenceMark: {}", sequenceMark);
		} finally {
			echoListeners.remove(echoToken);
		}
	}

	@Value
	private static class MongoResumeTokenSequenceMark {
		String tokenData;
		@Override public String toString() { return tokenData; }
	}

	/**
	 * The fields of the main MongoDB document.  Case-sensitive.
	 *
	 * No field name should be a prefix of any other.
	 */
	private enum TenantFields {
		state,
		echo,
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriver.class);
}
