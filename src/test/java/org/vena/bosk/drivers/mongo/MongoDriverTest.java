package org.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.BsonPlugin;
import org.vena.bosk.Catalog;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.ListingEntry;
import org.vena.bosk.ListingReference;
import org.vena.bosk.Mapping;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.BufferingDriver;
import org.vena.bosk.drivers.DriverConformanceTest;
import org.vena.bosk.exceptions.InvalidTypeException;

import static com.mongodb.ReadPreference.secondaryPreferred;
import static java.lang.Long.max;
import static java.lang.System.currentTimeMillis;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.vena.bosk.ListingEntry.LISTING_ENTRY;

@Testcontainers
class MongoDriverTest extends DriverConformanceTest {
	public static final String TEST_DB = "testDB";
	public static final String TEST_COLLECTION = "testCollection";
	protected static final Identifier entity123 = Identifier.from("123");
	protected static final Identifier entity124 = Identifier.from("124");
	protected static final Identifier rootID = Identifier.from("root");

	private final Deque<Consumer<MongoClient>> tearDownActions = new ArrayDeque<>();

	private static final Network NETWORK = Network.newNetwork();

	@Container
	private static final GenericContainer<?> MONGO_CONTAINER = new GenericContainer<>(
		new ImageFromDockerfile().withDockerfileFromBuilder(builder -> builder
			.from("mongo:4.0")
			.run("echo \"rs.initiate()\" > /docker-entrypoint-initdb.d/rs-initiate.js")
			.cmd("mongod", "--replSet", "rsLonesome", "--port", "27017", "--bind_ip_all")
			.build()))
		.withNetwork(NETWORK)
		.withExposedPorts(27017);

	@Container
	private static final ToxiproxyContainer TOXIPROXY_CONTAINER = new ToxiproxyContainer(
		DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.2.0").asCompatibleSubstituteFor("shopify/toxiproxy"))
		.withNetwork(NETWORK);

	private static ToxiproxyContainer.ContainerProxy proxy;

	private static MongoClientSettings clientSettings;
	private static MongoDriverSettings driverConfig;

	@BeforeAll
	static void setupDatabase() {
		proxy = TOXIPROXY_CONTAINER.getProxy(MONGO_CONTAINER, 27017);
		int initialTimeoutMS = 60_000;
		int queryTimeoutMS = 5_000; // Don't wait an inordinately long time for network outage testing
		clientSettings = MongoClientSettings.builder()
			.readPreference(secondaryPreferred())
			.applyToClusterSettings(builder -> {
				builder.hosts(singletonList(new ServerAddress(proxy.getContainerIpAddress(), proxy.getProxyPort())));
				builder.serverSelectionTimeout(initialTimeoutMS, MILLISECONDS);
			})
			.applyToSocketSettings(builder -> {
				builder.connectTimeout(initialTimeoutMS, MILLISECONDS);
				builder.readTimeout(queryTimeoutMS, MILLISECONDS);
			})
			.build();
		driverConfig = MongoDriverSettings.builder()
			.database(TEST_DB)
			.collection(TEST_COLLECTION)
			.build();
	}

	@AfterAll
	static void deleteDatabase() {
		MongoClient mongoClient = MongoClients.create(clientSettings);
		mongoClient.getDatabase(TEST_DB).drop();
		mongoClient.close();
	}

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = createDriverFactory();
	}

	@AfterEach
	void runTearDown() {
		MongoClient mongoClient = MongoClients.create(clientSettings);
		tearDownActions.forEach(a -> a.accept(mongoClient));
		mongoClient.close();
	}

	@Test
	void testWarmStart() throws InvalidTypeException, InterruptedException {
		Bosk<TestEntity> setupBosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		CatalogReference<TestEntity> catalogRef = setupBosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.catalog));
		ListingReference<TestEntity> listingRef = setupBosk.listingReference(TestEntity.class, Path.just(TestEntity.Fields.listing));

		// Make a change to the bosk so it's not just the initial root
		setupBosk.driver().submitReplacement(listingRef.then(entity123), LISTING_ENTRY);
		setupBosk.driver().flush();
		TestEntity expected = initialRoot(setupBosk)
			.withListing(Listing.of(catalogRef, entity123));

		Bosk<TestEntity> latecomerBosk = new Bosk<TestEntity>("Latecomer bosk", TestEntity.class, b->{
			throw new AssertionError("Default root function should not be called");
		}, driverFactory);

		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = latecomerBosk.readContext()) {
			TestEntity actual = latecomerBosk.rootReference().value();
			assertEquals(expected, actual);
		}

	}

	@Test
	void testFlush() throws InvalidTypeException, InterruptedException {
		// Set up MongoDriver writing to a modified BufferingDriver that lets us
		// have tight control over all the comings and goings from MongoDriver.
		BlockingQueue<Reference<?>> replacementsSeen = new LinkedBlockingDeque<>();
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot,
			(d,b) -> driverFactory.apply(new BufferingDriver<TestEntity>(d){
				@Override
				public <T> void submitReplacement(Reference<T> target, T newValue) {
					super.submitReplacement(target, newValue);
					replacementsSeen.add(target);
				}
			}, b));

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

	@Test
	void testListing() throws InvalidTypeException, InterruptedException {
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

	@Test
	void testNetworkOutage() throws InvalidTypeException, InterruptedException {
		Bosk<TestEntity> bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, driverFactory);
		BoskDriver<TestEntity> driver = bosk.driver();
		CatalogReference<TestEntity> catalogRef = bosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.catalog));
		ListingReference<TestEntity> listingRef = bosk.listingReference(TestEntity.class, Path.just(TestEntity.Fields.listing));

		// Wait till MongoDB is up and running
		driver.flush();

		proxy.setConnectionCut(true);

		assertThrows(MongoException.class, driver::flush);

		proxy.setConnectionCut(false);

		// Make a change to the bosk and verify that it gets through
		driver.submitReplacement(listingRef.then(entity123), LISTING_ENTRY);
		TestEntity expected = initialRoot(bosk)
			.withListing(Listing.of(catalogRef, entity123));


		driver.flush();
		TestEntity actual;
		try (@SuppressWarnings("unused") Bosk<?>.ReadContext readContext = bosk.readContext()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	private BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>> createDriverFactory() {
		return (downstream, bosk) -> {
			MongoDriver<TestEntity> driver = new MongoDriver<>(
				downstream,
				bosk,
				clientSettings,
				driverConfig,
				rootID,
				new BsonPlugin());
			tearDownActions.addFirst(mongoClient->{
				driver.close();
				mongoClient
					.getDatabase(driverConfig.database())
					.getCollection(driverConfig.collection())
					.drop();
			});
			return driver;
		};
	}

	private TestEntity initialRoot(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		Reference<Catalog<TestEntity>> catalogRef = testEntityBosk.catalogReference(TestEntity.class, Path.just(
				TestEntity.Fields.catalog
		));
		Reference<Catalog<TestEntity>> anyChildCatalog = testEntityBosk.catalogReference(TestEntity.class, Path.of(
			TestEntity.Fields.catalog, "-child-", TestEntity.Fields.catalog
		));
		return new TestEntity(rootID,
			rootID.toString(),
			Catalog.of(
				TestEntity.empty(entity123, anyChildCatalog.boundTo(entity123)),
				TestEntity.empty(entity124, anyChildCatalog.boundTo(entity124))
			),
			Listing.empty(catalogRef),
			Mapping.empty(catalogRef),
			Optional.empty()
		);
	}

}
