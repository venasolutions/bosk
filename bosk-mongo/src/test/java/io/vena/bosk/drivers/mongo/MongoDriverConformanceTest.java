package io.vena.bosk.drivers.mongo;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.drivers.DriverConformanceTest;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.drivers.mongo.SingleDocumentMongoDriver.COLLECTION_NAME;

@UsesMongoService
class MongoDriverConformanceTest extends DriverConformanceTest {
	public static final String TEST_DB = MongoDriverConformanceTest.class.getSimpleName() + "_DB";

	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;

	@BeforeAll
	static void setupMongoConnection() {
		mongoService = new MongoService();
	}

	@BeforeEach
	void setupDriverFactory() {
		driverFactory = createDriverFactory();
	}

	@AfterEach
	void runTearDown() {
		tearDownActions.forEach(Runnable::run);
	}

	//@AfterAll
	static void deleteDatabase() {
		mongoService.client().getDatabase(TEST_DB).drop();
		mongoService.close();
	}

	private <E extends Entity> DriverFactory<E> createDriverFactory() {
		MongoDriverSettings driverSettings = MongoDriverSettings.builder()
			.database(TEST_DB)
			.testing(MongoDriverSettings.Testing.builder()
				.eventDelayMS(20)
				.build())
			.build();
		return (bosk, downstream) -> {
			MongoDriver<E> driver = MongoDriver.<E>factory(
				mongoService.clientSettings(), driverSettings, new BsonPlugin()
			).build(bosk, downstream);
			tearDownActions.addFirst(()->{
				driver.close();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(COLLECTION_NAME)
					.drop();
			});
			return driver;
		};
	}

}
