package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.v3.InitialRootFailureException;
import io.vena.bosk.drivers.state.TestEntity;
import org.junit.jupiter.api.Test;

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
