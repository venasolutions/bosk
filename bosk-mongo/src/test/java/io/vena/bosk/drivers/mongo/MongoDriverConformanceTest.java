package io.vena.bosk.drivers.mongo;

import io.vena.bosk.DriverFactory;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.DriverConformanceTest;
import io.vena.bosk.drivers.mongo.TestParameters.EventTiming;
import io.vena.bosk.drivers.mongo.TestParameters.ParameterSet;
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
	public MongoDriverConformanceTest(ParameterSet parameters) {
		this.driverSettings = parameters.driverSettingsBuilder().build();
	}

	@SuppressWarnings("unused")
	static Stream<ParameterSet> parameters() {
		return TestParameters.driverSettings(
			Stream.of(
				PandoFormat.oneBigDocument(),
				PandoFormat.withGraftPoints("/catalog", "/sideTable"), // Basic
				PandoFormat.withGraftPoints("/catalog/-x-/sideTable", "/sideTable/-x-/catalog", "/sideTable/-x-/sideTable/-y-/catalog"), // Nesting, parameters
				PandoFormat.withGraftPoints("/sideTable/-x-/sideTable/-y-/catalog"), // Multiple parameters in the not-separated part
				SEQUOIA
			),
			Stream.of(EventTiming.NORMAL) // EARLY is slow; LATE is really slow
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
