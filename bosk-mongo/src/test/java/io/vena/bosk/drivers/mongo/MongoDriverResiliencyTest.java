package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoCollection;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Listing;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.var;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonNull;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.RESILIENT;
import static io.vena.bosk.drivers.mongo.v2.MainDriver.COLLECTION_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A set of tests that only work with {@link io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind#RESILIENT}
 */
public class MongoDriverResiliencyTest extends AbstractMongoDriverTest {
	@ParametersByName
	public MongoDriverResiliencyTest(MongoDriverSettings.MongoDriverSettingsBuilder driverSettings) {
		super(driverSettings);
	}

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettings.MongoDriverSettingsBuilder> driverSettings() {
		return Stream.of(
			MongoDriverSettings.builder()
				.database("boskResiliencyTestDB_" + dbCounter.incrementAndGet())
				.implementationKind(RESILIENT),
			MongoDriverSettings.builder()
				.database("boskResiliencyTestDB_" + dbCounter.incrementAndGet() + "_late")
				.implementationKind(RESILIENT)
				.testing(MongoDriverSettings.Testing.builder()
					.eventDelayMS(200)
					.build()),
			MongoDriverSettings.builder()
				.database("boskResiliencyTestDB_" + dbCounter.incrementAndGet() + "_early")
				.implementationKind(RESILIENT)
				.testing(MongoDriverSettings.Testing.builder()
					.eventDelayMS(-200)
					.build())
		);
	}

	@ParametersByName
	@DisruptsMongoService
	void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
		// Set up the database contents to be different from initialRoot
		TestEntity initialState = initializeDatabase("distinctive string");

		mongoService.proxy().setConnectionCut(true);

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		MongoDriverSpecialTest.Refs refs = bosk.buildReferences(MongoDriverSpecialTest.Refs.class);
		BoskDriver<TestEntity> driver = bosk.driver();
		TestEntity defaultState = initialRoot(bosk);

		try (var __ = bosk.readContext()) {
			assertEquals(defaultState, bosk.rootReference().value(),
				"Uses default state if database is unavailable");
		}

		assertThrows(FlushFailureException.class, driver::flush,
			"Flush disallowed during outage");
		assertThrows(Exception.class, () -> driver.submitReplacement(bosk.rootReference(), initialRoot(bosk)),
			"Updates disallowed during outage");

		mongoService.proxy().setConnectionCut(false);

		driver.flush();
		try (var __ = bosk.readContext()) {
			assertEquals(initialState, bosk.rootReference().value(),
				"Updates to database state once it reconnects");
		}

		// Make a change to the bosk and verify that it gets through
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withString("distinctive string")
			.withListing(Listing.of(refs.catalog(), entity123));


		driver.flush();
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			assertEquals(expected, bosk.rootReference().value());
		}
	}

	@ParametersByName
	@UsesMongoService
	void databaseDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop database");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.drop();
		}, (b) -> initializeDatabase("after deletion"));
	}

	@ParametersByName
	@UsesMongoService
	void collectionDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop collection");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (b) -> initializeDatabase("after deletion"));
	}

	@ParametersByName
	@UsesMongoService
	void documentDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Delete document");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.deleteMany(new BsonDocument());
		}, (b) -> initializeDatabase("after deletion"));
	}

	@ParametersByName
	@UsesMongoService
	void documentReappears_recovers() throws InvalidTypeException, InterruptedException, IOException {
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME);
		AtomicReference<Document> originalDocument = new AtomicReference<>();
		testRecovery(() -> {
			LOGGER.debug("Save original document");
			try (var cursor = collection.find().cursor()) {
				originalDocument.set(cursor.next());
			}
			LOGGER.debug("Delete document");
			collection.deleteMany(new BsonDocument());
		}, (b) -> {
			LOGGER.debug("Restore original document");
			collection.insertOne(originalDocument.get());
			return b;
		});
	}

	@ParametersByName
	@UsesMongoService
	void revisionDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		// TODO: need a more complete test here. Deleting the revision
		// field should be the same as setting it to zero, and both should work.
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before deletion");

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk " + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory);
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Delete revision field");
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
			.updateOne(
				new BsonDocument(),
				new BsonDocument("$unset", new BsonDocument(DocumentFields.revision.name(), new BsonNull())) // Value is ignored
			);

		LOGGER.debug("Ensure flush works");
		bosk.driver().flush();
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Repair by setting revision in the far future");
		setRevision(1000L);

		LOGGER.debug("Ensure flush works again");
		bosk.driver().flush();
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}
	}

	private void setRevision(long revisionNumber) {
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
			.updateOne(
				new BsonDocument(),
				new BsonDocument("$set", new BsonDocument(DocumentFields.revision.name(), new BsonInt64(revisionNumber))) // Value is ignored
			);
	}

	private TestEntity initializeDatabase(String distinctiveString) {
		try {
			Bosk<TestEntity> prepBosk = new Bosk<TestEntity>(
				"Prep bosk " + boskCounter.incrementAndGet(),
				TestEntity.class,
				bosk -> initialRoot(bosk).withString(distinctiveString),
				driverFactory);
			MongoDriver<TestEntity> driver = (MongoDriver<TestEntity>) prepBosk.driver();
			driver.flush();
			driver.close();

			return initialRoot(prepBosk).withString(distinctiveString);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException, InvalidTypeException {
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before disruption");

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk " + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory);
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Run disruptive action");
		disruptiveAction.run();

		LOGGER.debug("Ensure flush throws");
		assertThrows(FlushFailureException.class, () -> bosk.driver().flush());
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Run recovery action");
		TestEntity afterState = recoveryAction.apply(beforeState);

		LOGGER.debug("Ensure flush works");
		bosk.driver().flush();
		try (var __ = bosk.readContext()) {
			assertEquals(afterState, bosk.rootReference().value());
		}
	}

	private static final AtomicInteger dbCounter = new AtomicInteger(0);
	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverResiliencyTest.class);
}
