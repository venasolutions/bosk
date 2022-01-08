package org.vena.bosk.drivers.mongo;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.UpdateResult;
import java.lang.reflect.Type;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Value;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.BsonPlugin;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.mongo.Formatter.TenantFields;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NotYetImplementedException;

import static com.mongodb.ErrorCategory.DUPLICATE_KEY;
import static java.lang.String.format;
import static org.bson.BsonBoolean.FALSE;
import static org.vena.bosk.drivers.mongo.Formatter.dottedFieldNameOf;
import static org.vena.bosk.drivers.mongo.Formatter.enclosingReference;

public final class MongoDriver<R extends Entity> implements BoskDriver<R> {
	private final Formatter formatter;
	private final MongoReceiver<R> receiver;
	private final MongoClient mongoClient;
	private final MongoCollection<Document> collection;
	private final BsonString tenantID;
	private final Reference<R> rootRef;
	private final String echoPrefix;
	private final AtomicLong echoCounter = new AtomicLong(1_000_000_000_000L); // Start with a big number so the length doesn't change often

	public MongoDriver(BoskDriver<R> downstream, Bosk<R> bosk, MongoClientSettings clientSettings, MongoDriverSettings driverSettings, Identifier tenantID, BsonPlugin bsonPlugin) {
		this.mongoClient = MongoClients.create(clientSettings);
		this.formatter = new Formatter(bosk, bsonPlugin);
		this.collection = mongoClient
			.getDatabase(driverSettings.database())
			.getCollection(driverSettings.collection());
		this.receiver = new ChangeStreamMongoReceiver<>(downstream, bosk.rootReference(), collection, formatter);
		this.echoPrefix = bosk.instanceID().toString();
		this.tenantID = new BsonString(tenantID.toString());
		this.rootRef = bosk.rootReference();

	}

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
				return formatter.document2object(newState, rootRef);
			}
		} catch (NoSuchElementException e) {
			LOGGER.debug("| No tenant document; delegating downstream");
		}

		R root = receiver.initialRoot(rootType);
		ensureTenantDocumentExists(formatter.object2bsonValue(root, rootType));
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

	@Override
	public void flush() throws InterruptedException {
		LOGGER.debug("+ flush");
		flushToDownstreamDriver();
		receiver.flushDownstream();
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

	public void close() {
		try {
			receiver.close();
		} finally {
			mongoClient.close();
		}
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
		BsonValue value = formatter.object2bsonValue(newValue, target.targetType());
		LOGGER.debug("| Set field {}: {}", key, value);
		return new BsonDocument("$set", new BsonDocument(key, value));
	}

	private <T> BsonDocument deletionDoc(Reference<T> target) {
		String key = dottedFieldNameOf(target, rootRef);
		LOGGER.debug("| Unset field {}", key);
		return new BsonDocument("$unset", new BsonDocument(key, new BsonNull())); // Value is ignored
	}

	private void ensureTenantDocumentExists(BsonValue initialState) {
		BsonDocument filter = tenantFilter();
		BsonDocument update = initialTenantUpsert(initialState);
		UpdateOptions options = new UpdateOptions();
		options.upsert(true);
		LOGGER.debug("** Initial tenant upsert for {}", tenantID);
		LOGGER.trace("| Filter: {}", filter);
		LOGGER.trace("| Update: {}", update);
		LOGGER.trace("| Options: {}", options);
		UpdateResult result;
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

	//
	// Echo helper logic
	//

	private String uniqueEchoToken() {
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
			receiver.putEchoListener(echoToken, listener);
			BsonDocument updateDoc = new BsonDocument("$set", new BsonDocument(TenantFields.echo.name(), new BsonString(echoToken)));
			LOGGER.debug("| Update: {}", updateDoc);
			collection.updateOne(tenantFilter(), updateDoc);
			LOGGER.debug("| Waiting");
			BsonDocument resumeToken = listener.take();
			MongoResumeTokenSequenceMark sequenceMark = new MongoResumeTokenSequenceMark(resumeToken.getString("_data").getValue());
			LOGGER.debug("| SequenceMark: {}", sequenceMark);
		} finally {
			receiver.removeEchoListener(echoToken);
		}
	}

	@Value
	private static class MongoResumeTokenSequenceMark {
		String tokenData;
		@Override public String toString() { return tokenData; }
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriver.class);
}
