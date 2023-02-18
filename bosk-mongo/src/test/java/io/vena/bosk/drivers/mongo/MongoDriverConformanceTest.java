package io.vena.bosk.drivers.mongo;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.drivers.DriverConformanceTest;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import io.vena.bosk.junit.ParametersByName;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.drivers.mongo.SingleDocumentMongoDriver.COLLECTION_NAME;

@UsesMongoService
class MongoDriverConformanceTest extends DriverConformanceTest implements TestParameters {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;
	private final MongoDriverSettings driverSettings;

	@ParametersByName
	public MongoDriverConformanceTest(MongoDriverSettingsBuilder driverSettings) {
		this.driverSettings = driverSettings.build();
	}

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

	private <E extends Entity> DriverFactory<E> createDriverFactory() {
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
