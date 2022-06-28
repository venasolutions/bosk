package org.vena.bosk.drivers.mongo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.BsonPlugin;
import org.vena.bosk.Entity;
import org.vena.bosk.drivers.DriverConformanceTest;

@UsesMongoService
class MongoDriverConformanceTest extends DriverConformanceTest {
	public static final String TEST_DB = MongoDriverConformanceTest.class.getSimpleName() + "_DB";
	public static final String TEST_COLLECTION = "testCollection";

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

	private <E extends Entity> BiFunction<BoskDriver<E>, Bosk<E>, BoskDriver<E>> createDriverFactory() {
		MongoDriverSettings driverSettings = MongoDriverSettings.builder()
			.database(TEST_DB)
			.collection(TEST_COLLECTION)
			.build();
		return (downstream, bosk) -> {
			MongoDriver<E> driver = new MongoDriver<>(
				downstream,
				bosk,
				mongoService.clientSettings(),
				driverSettings,
				new BsonPlugin());
			tearDownActions.addFirst(()->{
				driver.close();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.getCollection(driverSettings.collection())
					.drop();
			});
			return driver;
		};
	}

}
