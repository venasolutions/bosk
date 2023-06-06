package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.RESILIENT;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.STABLE;

public interface TestParameters {
	AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		String prefix = "boskTestDB_" + dbCounter.incrementAndGet();
		return Stream.of(
			MongoDriverSettings.builder()
				.database(prefix + "_stable")
				.implementationKind(STABLE),
			MongoDriverSettings.builder()
				.database(prefix + "_resilient")
				.implementationKind(RESILIENT)
//			MongoDriverSettings.builder()
//				.database(prefix + "_slow")
//				.implementationKind(RESILIENT)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(200)
//					.build()),
//			MongoDriverSettings.builder()
//				.database(prefix + "_fast")
//				.implementationKind(RESILIENT)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(-200)
//					.build())
		);
	}

}
