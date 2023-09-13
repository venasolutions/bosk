package io.vena.bosk.drivers.mongo;

import com.mongodb.client.MongoCollection;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Listing;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.drivers.mongo.TestParameters.EarlyOrLate;
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
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static ch.qos.logback.classic.Level.ERROR;
import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.drivers.mongo.MainDriver.COLLECTION_NAME;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A set of tests that only work with resilient drivers.
 */
public class MongoDriverResiliencyTest extends AbstractMongoDriverTest {
	FlushOrWait flushOrWait;

	@BeforeEach
	void setupLogging() {
		// This test deliberately provokes a lot of warnings, so log errors only
		setLogging(ERROR, MongoDriver.class.getPackage());
	}

	@ParametersByName
	MongoDriverResiliencyTest(FlushOrWait flushOrWait, MongoDriverSettings.MongoDriverSettingsBuilder driverSettings) {
		super(driverSettings);
		this.flushOrWait = flushOrWait;
	}

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettings.MongoDriverSettingsBuilder> driverSettings() {
		return TestParameters.driverSettings(
			Stream.of(
				SEQUOIA,
				PandoFormat.withSeparateCollections("/catalog", "/sideTable")
			),
			Stream.of(EarlyOrLate.NORMAL)
		).map(b -> b
			.recoveryPollingMS(3000) // Note that some tests can take as long as 10x this
			.flushTimeoutMS(4000) // A little more than recoveryPollingMS
		);
	}

	enum FlushOrWait { FLUSH, WAIT };

	@SuppressWarnings("unused")
	static Stream<FlushOrWait> flushOrWait() {
		return Stream.of(FlushOrWait.values());
	}

	@ParametersByName
	@DisruptsMongoService
	void initialOutage_recovers() throws InvalidTypeException, InterruptedException, IOException {
		LOGGER.debug("Set up the database contents to be different from initialRoot");
		TestEntity initialState = initializeDatabase("distinctive string");

		LOGGER.debug("Cut mongo connection");
		mongoService.proxy().setConnectionCut(true);
		tearDownActions.add(()->mongoService.proxy().setConnectionCut(false));

		LOGGER.debug("Create a new bosk that can't connect");
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test " + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory);
		MongoDriverSpecialTest.Refs refs = bosk.buildReferences(MongoDriverSpecialTest.Refs.class);
		BoskDriver<TestEntity> driver = bosk.driver();
		TestEntity defaultState = initialRoot(bosk);

		try (var __ = bosk.readContext()) {
			assertEquals(defaultState, bosk.rootReference().value(),
				"Uses default state if database is unavailable");
		}

		LOGGER.debug("Verify that driver operations throw");
		assertThrows(FlushFailureException.class, driver::flush,
			"Flush disallowed during outage");
		assertThrows(Exception.class, () -> driver.submitReplacement(bosk.rootReference(), initialRoot(bosk)),
			"Updates disallowed during outage");

		LOGGER.debug("Restore mongo connection");
		mongoService.proxy().setConnectionCut(false);

		LOGGER.debug("Flush and check that the state updates");
		waitFor(driver);
		try (var __ = bosk.readContext()) {
			assertEquals(initialState, bosk.rootReference().value(),
				"Updates to database state once it reconnects");
		}

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withString("distinctive string")
			.withListing(Listing.of(refs.catalog(), entity123));


		waitFor(driver);
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			assertEquals(expected, bosk.rootReference().value());
		}
	}

	private void waitFor(BoskDriver<TestEntity> driver) throws IOException, InterruptedException {
		switch (flushOrWait) {
			case FLUSH:
				driver.flush();
				break;
			case WAIT:
				Thread.sleep(2 * driverSettings.recoveryPollingMS());
				break;
		}
	}

	@ParametersByName
	@UsesMongoService
	void databaseDropped_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop database");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.drop();
		}, (b) -> initializeDatabase("after drop"));
	}

	@ParametersByName
	@UsesMongoService
	void collectionDropped_recovers() throws InvalidTypeException, InterruptedException, IOException {
		testRecovery(() -> {
			LOGGER.debug("Drop collection");
			mongoService.client()
				.getDatabase(driverSettings.database())
				.getCollection(COLLECTION_NAME)
				.drop();
		}, (b) -> initializeDatabase("after drop"));
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
		BsonString rootDocumentID = (driverSettings.preferredDatabaseFormat() == SEQUOIA)?
			SequoiaFormatDriver.DOCUMENT_ID :
			PandoFormatDriver.ROOT_DOCUMENT_ID;
		BsonDocument rootDocumentFilter = new BsonDocument("_id", rootDocumentID);
		testRecovery(() -> {
			LOGGER.debug("Save original document");
			try (var cursor = collection.find(rootDocumentFilter).cursor()) {
				originalDocument.set(cursor.next());
			}
			LOGGER.debug("Delete document");
			collection.deleteMany(rootDocumentFilter);
		}, (b) -> {
			LOGGER.debug("Restore original document");
			collection.insertOne(originalDocument.get());
			return b;
		});
	}

	@ParametersByName
	@UsesMongoService
	void revisionDeleted_recovers() throws InvalidTypeException, InterruptedException, IOException {
		// It's not clear that this is a valid test. If this test is a burden to support,
		// we can consider removing it.
		//
		// In general, changing the revision field to a lower number is not fair to bosk
		// unless you also revert to the corresponding state. (And deleting the revision
		// field is conceptually equivalent to setting it to zero.) Deleting the revision field
		// is a special case because no ordinary bosk operations delete the revision field, or
		// set it to zero, so it's not unreasonable to expect bosk to handle this; but it's
		// also not reasonable to be surprised if it didn't.
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before deletion");

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test " + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory);
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
		waitFor(bosk.driver());
		try (var __ = bosk.readContext()) {
			assertEquals(beforeState, bosk.rootReference().value());
		}

		LOGGER.debug("Repair by setting revision in the far future");
		setRevision(1000L);

		LOGGER.debug("Ensure flush works again");
		waitFor(bosk.driver());
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
				"Prep " + boskCounter.incrementAndGet(),
				TestEntity.class,
				bosk -> initialRoot(bosk).withString(distinctiveString),
				driverFactory);
			MongoDriver<TestEntity> driver = (MongoDriver<TestEntity>) prepBosk.driver();
			waitFor(driver);
			driver.close();

			return initialRoot(prepBosk).withString(distinctiveString);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private void testRecovery(Runnable disruptiveAction, Function<TestEntity, TestEntity> recoveryAction) throws IOException, InterruptedException, InvalidTypeException {
		LOGGER.debug("Setup database to beforeState");
		TestEntity beforeState = initializeDatabase("before disruption");

		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test " + boskCounter.incrementAndGet(), TestEntity.class, this::initialRoot, driverFactory);
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
		waitFor(bosk.driver());
		try (var __ = bosk.readContext()) {
			assertEquals(afterState, bosk.rootReference().value());
		}
	}

	private static final AtomicInteger dbCounter = new AtomicInteger(0);
	private static final AtomicInteger boskCounter = new AtomicInteger(0);

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverResiliencyTest.class);
}
