package io.vena.bosk.drivers.mongo;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.DriverConformanceTest;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import io.vena.bosk.junit.ParametersByName;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;

@UsesMongoService
class MongoDriverConformanceTest extends DriverConformanceTest {
	private final Deque<Runnable> tearDownActions = new ArrayDeque<>();
	private static MongoService mongoService;
	private final MongoDriverSettings driverSettings;

	@ParametersByName
	public MongoDriverConformanceTest(MongoDriverSettingsBuilder driverSettings) {
		this.driverSettings = driverSettings.build();
	}

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		return TestParameters.driverSettings(
			Stream.of(
				SEQUOIA,
				PandoFormat.oneBigDocument(),
				PandoFormat.withSeparateCollections("/catalog", "/sideTable")),
			Stream.of(TestParameters.EarlyOrLate.values())
		);
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

	private <R extends StateTreeNode> DriverFactory<R> createDriverFactory() {
		return (bosk, downstream) -> {
			MongoDriver<R> driver = MongoDriver.<R>factory(
				mongoService.clientSettings(), driverSettings, new BsonPlugin()
			).build(bosk, downstream);
			tearDownActions.addFirst(()->{
				driver.close();
				mongoService.client()
					.getDatabase(driverSettings.database())
					.drop();
			});
			return driver;
		};
	}

}
