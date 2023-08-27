package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.PANDO;

public interface TestParameters {
	AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		String prefix = "boskTestDB_" + dbCounter.incrementAndGet();
		return Stream.of(
			MongoDriverSettings.builder()
				.database(prefix),
			MongoDriverSettings.builder()
				.preferredDatabaseFormat(PANDO)
				.database(prefix + "_pando")
//			MongoDriverSettings.builder()
//				.database(prefix + "_slow")
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(200)
//					.build()),
//			MongoDriverSettings.builder()
//				.database(prefix + "_fast")
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(-200)
//					.build())
		);
	}

}
