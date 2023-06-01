package io.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.ListingReference;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTable;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.BufferingDriver;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.drivers.state.TestValues;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import lombok.Value;
import org.bson.BsonDocument;
import org.bson.BsonNull;
import org.bson.BsonString;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.drivers.mongo.Formatter.DocumentFields.path;
import static io.vena.bosk.drivers.mongo.Formatter.DocumentFields.revision;
import static io.vena.bosk.drivers.mongo.SingleDocumentMongoDriver.COLLECTION_NAME;
import static java.lang.Long.max;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for MongoDB-specific functionality
 */
class MongoDriverSpecialTest implements TestParameters {

	private static final Identifier entity123 = Identifier.from("123");
	private static final Identifier entity124 = Identifier.from("124");
	private static final Identifier rootID = Identifier.from("root");

	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;

	private DriverFactory<TestEntity> driverFactory;
	private final MongoDriverSettings driverSettings;

	public interface Refs {
		@ReferencePath("/catalog") CatalogReference<TestEntity> catalog();
		@ReferencePath("/listing/-entity-") Reference<ListingEntry> listingEntry(Identifier entity);
		@ReferencePath("/catalog/-child-/catalog") CatalogReference<TestEntity> childCatalog(Identifier child);
	}

	@ParametersByName
	public MongoDriverSpecialTest(MongoDriverSettingsBuilder driverSettings) {
		this.driverSettings = driverSettings.build();
	}

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = createDriverFactory();

		// Start with a clean slate
		mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME)
			.drop();
	}

	@AfterEach
	void runTearDown() {
		tearDownActions.forEach(Runnable::run);
	}

	@ParametersByName
	@UsesMongoService
	void warmStart_stateMatches() throws InvalidTypeException, InterruptedException, IOException {
		Bosk<TestEntity> setupBosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		Refs refs = setupBosk.buildReferences(Refs.class);

		// Make a change to the bosk so it's not just the initial root
		setupBosk.driver().submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		setupBosk.driver().flush();
		TestEntity expected = initialRoot(setupBosk)
			.withListing(Listing.of(refs.catalog(), entity123));

		Bosk<TestEntity> latecomerBosk = new Bosk<TestEntity>("Latecomer bosk", TestEntity.class, b->{
			throw new AssertionError("Default root function should not be called");
		}, driverFactory);

		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = latecomerBosk.readContext()) {
			TestEntity actual = latecomerBosk.rootReference().value();
			assertEquals(expected, actual);
		}

	}

	@ParametersByName
	@UsesMongoService
	void flush_localStateUpdated() throws InvalidTypeException, InterruptedException, IOException {
		// Set up MongoDriver writing to a modified BufferingDriver that lets us
		// have tight control over all the comings and goings from MongoDriver.
		BlockingQueue<Reference<?>> replacementsSeen = new LinkedBlockingDeque<>();
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot,
			(b,d) -> driverFactory.build(b, new BufferingDriver<TestEntity>(d){
				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) {
					super.submitReplacement(target, newValue);
					replacementsSeen.add(target);
				}
			}));

		CatalogReference<TestEntity> catalogRef = bosk.rootReference().thenCatalog(TestEntity.class,
			TestEntity.Fields.catalog);
		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class,
			TestEntity.Fields.listing);

		// Make a change
		Reference<ListingEntry> ref = listingRef.then(entity123);
		bosk.driver().submitReplacement(ref, LISTING_ENTRY);

		// Give the driver a bit of time to make a mistake, if it's going to
		long budgetMillis = 2000;
		while (budgetMillis > 0) {
			long startTime = currentTimeMillis();
			Reference<?> updatedRef = replacementsSeen.poll(budgetMillis, MILLISECONDS);
			if (ref.equals(updatedRef)) {
				// We've seen the expected update. This is pretty likely to be a good time
				// to proceed with the test.
				break;
			} else {
				long elapsedTime = currentTimeMillis() - startTime;
				budgetMillis -= max(elapsedTime, 1); // Always make progress despite the vagaries of the system clock
			}
		}

		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = bosk.readContext()) {
			TestEntity expected = initialRoot(bosk);
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver should not have called downstream.flush() yet");
		}

		bosk.driver().flush();

		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = bosk.readContext()) {
			TestEntity expected = initialRoot(bosk).withListing(Listing.of(catalogRef, entity123));
			TestEntity actual = bosk.rootReference().value();
			assertEquals(expected, actual, "MongoDriver.flush() should reliably update the bosk");
		}

	}

	@ParametersByName
	@UsesMongoService
	void listing_stateMatches() throws InvalidTypeException, InterruptedException, IOException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		BoskDriver<TestEntity> driver = bosk.driver();
		CatalogReference<TestEntity> catalogRef = bosk.rootReference().thenCatalog(TestEntity.class,
			TestEntity.Fields.catalog);
		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class,
			TestEntity.Fields.listing);

		// Clear the listing
		driver.submitReplacement(listingRef, Listing.empty(catalogRef));

		// Add to the listing
		driver.submitReplacement(listingRef.then(entity124), LISTING_ENTRY);
		driver.submitReplacement(listingRef.then(entity123), LISTING_ENTRY);
		driver.submitReplacement(listingRef.then(entity124), LISTING_ENTRY);

		// Check the contents
		driver.flush();
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			Listing<TestEntity> actual = listingRef.value();
			Listing<TestEntity> expected = Listing.of(catalogRef, entity124, entity123);
			assertEquals(expected, actual);
		}

		// Remove an entry
		driver.submitDeletion(listingRef.then(entity123));

		// Check the contents
		driver.flush();
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			Listing<TestEntity> actual = listingRef.value();
			Listing<TestEntity> expected = Listing.of(catalogRef, entity124);
			assertEquals(expected, actual);
		}
	}

	@ParametersByName
	@DisruptsMongoService
	void networkOutage_boskRecovers() throws InvalidTypeException, InterruptedException, IOException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Main", TestEntity.class, this::initialRoot, driverFactory);
		Refs refs = bosk.buildReferences(Refs.class);
		BoskDriver<TestEntity> driver = bosk.driver();

		LOGGER.debug("Wait till MongoDB is up and running");
		driver.flush();

		LOGGER.debug("Make another bosk that doesn't witness any change stream events before the outage");
		Bosk<TestEntity> latecomerBosk = new Bosk<TestEntity>("Latecomer", TestEntity.class, this::initialRoot, driverFactory);

		LOGGER.debug("Cut connection");
		mongoService.proxy().setConnectionCut(true);

		assertThrows(FlushFailureException.class, driver::flush);
		assertThrows(FlushFailureException.class, latecomerBosk.driver()::flush);

		LOGGER.debug("Reestablish connection");
		mongoService.proxy().setConnectionCut(false);

		LOGGER.debug("Make a change to the bosk and verify that it gets through");
		driver.submitReplacement(refs.listingEntry(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(refs.catalog(), entity123));


		driver.flush();
		TestEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);

		latecomerBosk.driver().flush();
		TestEntity latecomerActual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = latecomerBosk.readContext()) {
			latecomerActual = latecomerBosk.rootReference().value();
		}
		assertEquals(expected, latecomerActual);
	}

	@ParametersByName
	@UsesMongoService
	void initialStateHasNonexistentFields_ignored() {
		// Upon creating bosk, the initial value will be saved to MongoDB
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Newer bosk", TestEntity.class, this::initialRoot, driverFactory);

		// Upon creating prevBosk, the state in the database will be loaded into the local.
		Bosk<OldEntity> prevBosk = new Bosk<OldEntity>(
			"Older bosk",
			OldEntity.class,
			(b) -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			createDriverFactory());

		OldEntity expected = new OldEntity(rootID, rootID.toString());

		OldEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = prevBosk.readContext()) {
			actual = prevBosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	@ParametersByName
	@UsesMongoService
	void updateHasNonexistentFields_ignored() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Newer bosk", TestEntity.class, this::initialRoot, driverFactory);
		Bosk<OldEntity> prevBosk = new Bosk<OldEntity>(
			"Older bosk",
			OldEntity.class,
			(b) -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			createDriverFactory());

		TestEntity initialRoot = initialRoot(bosk);
		bosk.driver().submitReplacement(bosk.rootReference(),
			initialRoot
				.withString("replacementString")
				.withListing(Listing.of(initialRoot.listing().domain(), Identifier.from("newEntry"))));

		prevBosk.driver().flush();

		OldEntity expected = new OldEntity(rootID, "replacementString");

		OldEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = prevBosk.readContext()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(expected, actual);
	}

	@ParametersByName
	@UsesMongoService
	void updateNonexistentField_ignored() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Newer bosk", TestEntity.class, this::initialRoot, driverFactory);
		Bosk<OldEntity> prevBosk = new Bosk<OldEntity>(
			"Older bosk",
			OldEntity.class,
			(b) -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			createDriverFactory());

		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class, TestEntity.Fields.listing);

		TestEntity initialRoot = initialRoot(bosk);
		bosk.driver().submitReplacement(listingRef,
			Listing.of(initialRoot.listing().domain(), Identifier.from("newEntry")));

		prevBosk.driver().flush();

		OldEntity expected = new OldEntity(rootID, rootID.toString()); // unchanged

		OldEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = prevBosk.readContext()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(expected, actual);
	}

	@ParametersByName
	@UsesMongoService
	void deleteNonexistentField_ignored() throws InvalidTypeException, IOException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Newer bosk", TestEntity.class, this::initialRoot, driverFactory);
		Bosk<OldEntity> prevBosk = new Bosk<OldEntity>(
			"Older bosk",
			OldEntity.class,
			(b) -> { throw new AssertionError("prevBosk should use the state from MongoDB"); },
			createDriverFactory());

		ListingReference<TestEntity> listingRef = bosk.rootReference().thenListing(TestEntity.class, TestEntity.Fields.listing);

		bosk.driver().submitDeletion(listingRef.then(entity123));

		prevBosk.driver().flush();

		OldEntity expected = new OldEntity(rootID, rootID.toString()); // unchanged

		OldEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = prevBosk.readContext()) {
			actual = prevBosk.rootReference().value();
		}

		assertEquals(expected, actual);
	}

	@ParametersByName
	@UsesMongoService
	void refurbish_createsField() throws IOException, InterruptedException {
		// We'll use this as an honest observer of the actual state
		LOGGER.debug("Create Original bosk");
		Bosk<TestEntity> originalBosk = new Bosk<TestEntity>(
			"Original",
			TestEntity.class,
			this::initialRoot,
			createDriverFactory()
		);

		LOGGER.debug("Create Upgradeable bosk");
		Bosk<UpgradeableEntity> upgradeableBosk = new Bosk<UpgradeableEntity>(
			"Upgradeable",
			UpgradeableEntity.class,
			(b) -> { throw new AssertionError("upgradeableBosk should use the state from MongoDB"); },
			createDriverFactory()
		);

		LOGGER.debug("Check state before");
		Optional<TestValues> before;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = originalBosk.readContext()) {
			before = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.empty(), before); // Not there yet

		LOGGER.debug("Call refurbish");
		((MongoDriver<?>)upgradeableBosk.driver()).refurbish();
		originalBosk.driver().flush(); // Not the bosk that did refurbish!

		LOGGER.debug("Check state after");
		Optional<TestValues> after;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = originalBosk.readContext()) {
			after = originalBosk.rootReference().value().values();
		}
		assertEquals(Optional.of(TestValues.blank()), after); // Now it's there
	}

	@ParametersByName
	@UsesMongoService
	void refurbish_fixesMetadata() throws IOException, InterruptedException {
		// Set up the database so it looks basically right
		Bosk<TestEntity> initialBosk = new Bosk<TestEntity>(
			"Initial",
			TestEntity.class,
			this::initialRoot,
			createDriverFactory()
		);

		// (Close this so it doesn't crash when we delete the "path" field)
		((MongoDriver<TestEntity>)initialBosk.driver()).close();

		// Delete some metadata fields
		MongoCollection<Document> collection = mongoService.client()
			.getDatabase(driverSettings.database())
			.getCollection(COLLECTION_NAME);
		deleteFields(collection, path, revision);

		// Make the bosk whose refurbish operation we want to test
		Bosk<TestEntity> bosk = new Bosk<TestEntity>(
			"bosk",
			TestEntity.class,
			this::initialRoot,
			createDriverFactory()
		);

		// Get the new bosk reconnected
		bosk.driver().flush();

		// Simply connecting a new bosk repairs certain fields.
		// To test those, delete them again.
		// This may cause the receiver to throw an exception for deleting this field unexpectedly,
		// but it recovers, so that's ok.
		deleteFields(collection, revision);

		// Verify that the fields are indeed gone
		BsonDocument filterDoc = new BsonDocument("_id", new BsonString("boskDocument"));
		try (MongoCursor<Document> cursor = collection.find(filterDoc).cursor()) {
			Document doc = cursor.next();
			assertNull(doc.get(path.name()));
			assertNull(doc.get(revision.name()));
		}

		// Refurbish
		((MongoDriver<?>)bosk.driver()).refurbish();

		// Verify the fields are all now there
		try (MongoCursor<Document> cursor = collection.find(filterDoc).cursor()) {
			Document doc = cursor.next();
			assertEquals("/", doc.get(path.name()));
			assertEquals(1L, doc.getLong(revision.name()));
		}

	}

	private static void deleteFields(MongoCollection<Document> collection, DocumentFields... fields) {
		BsonDocument fieldsToUnset = new BsonDocument();
		for (DocumentFields field: fields) {
			fieldsToUnset.append(field.name(), new BsonNull()); // Value is ignored
		}
		BsonDocument filterDoc = new BsonDocument("_id", new BsonString("boskDocument"));
		collection.updateOne(
			filterDoc,
			new BsonDocument("$unset", fieldsToUnset));

		// Let's just make sure they're gone
		try (MongoCursor<Document> cursor = collection.find(filterDoc).cursor()) {
			Document doc = cursor.next();
			for (DocumentFields field: fields) {
				assertNull(doc.get(field.name()));
			}
		}
	}

	private <E extends Entity> DriverFactory<E> createDriverFactory() {
		return (bosk, downstream) -> {
			MongoDriver<E> driver = MongoDriver.<E>factory(
				MongoClientSettings.builder(mongoService.clientSettings())
					.applyToClusterSettings(builder -> {
						builder.serverSelectionTimeout(5, SECONDS);
					})
					.applyToSocketSettings(builder -> {
						// We're testing timeouts. Let's not wait too long.
						builder.readTimeout(5, SECONDS);
					})
					.build(),
				driverSettings,
				new BsonPlugin()
			).build(bosk, downstream);
			tearDownActions.addFirst(driver::close);
			return driver;
		};
	}

	@Value
	public static class OldEntity implements Entity {
		Identifier id;
		String string;
	}

	/**
	 * A version of {@link TestEntity} where the {@link Optional} {@link TestEntity#values()}
	 * field has a default (and some other fields have been deleted).
	 */
	@Value
	public static class UpgradeableEntity implements Entity {
		Identifier id;
		String string;
		Catalog<TestEntity> catalog;
		Listing<TestEntity> listing;
		SideTable<TestEntity, TestEntity> sideTable;
		Optional<TestValues> values;

		public Optional<TestValues> values() {
			return Optional.of(values.orElse(TestValues.blank()));
		}
	}

	private TestEntity initialRoot(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Refs refs = testEntityBosk.buildReferences(Refs.class);
		return new TestEntity(rootID,
			rootID.toString(),
			Catalog.of(
				TestEntity.empty(entity123, refs.childCatalog(entity123)),
				TestEntity.empty(entity124, refs.childCatalog(entity124))
			),
			Listing.of(refs.catalog(), entity123),
			SideTable.empty(refs.catalog()),
			Optional.empty()
		);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(MongoDriverSpecialTest.class);
}
