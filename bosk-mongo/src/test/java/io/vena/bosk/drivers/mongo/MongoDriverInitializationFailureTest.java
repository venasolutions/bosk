package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.state.TestEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static ch.qos.logback.classic.Level.ERROR;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.InitialDatabaseUnavailableMode.FAIL;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MongoDriverInitializationFailureTest extends AbstractMongoDriverTest {
	public MongoDriverInitializationFailureTest() {
		super(MongoDriverSettings.builder()
			.database(MongoDriverInitializationFailureTest.class.getSimpleName())
			.experimental(Experimental.builder()
				.build())
			.initialDatabaseUnavailableMode(FAIL));
	}

	@BeforeEach
	void setupLogging() {
		// This test deliberately provokes warnings, so log errors only
		setLogging(ERROR, MongoDriver.class.getPackage());
	}

	@Test
	@DisruptsMongoService
	void initialOutage_throws() {
		mongoService.proxy().setConnectionCut(true);
		tearDownActions.add(()->mongoService.proxy().setConnectionCut(false));
		assertThrows(InitialRootFailureException.class, ()->{
			new Bosk<TestEntity>("Fail", TestEntity.class, this::initialRoot, super.createDriverFactory());
		});
	}
}
