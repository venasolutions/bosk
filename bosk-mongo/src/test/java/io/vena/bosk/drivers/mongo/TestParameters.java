package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.RESILIENT3;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ImplementationKind.STABLE;

public interface TestParameters {
	AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		String prefix = "boskTestDB_" + dbCounter.incrementAndGet();
		Experimental resilient3 = Experimental.builder()
			.implementationKind(RESILIENT3)
			.build();
		return Stream.of(
			MongoDriverSettings.builder()
				.database(prefix + "_stable")
				.experimental(Experimental.builder().implementationKind(STABLE).build()),
			MongoDriverSettings.builder()
				.database(prefix + "_resilient3")
				.experimental(resilient3)
//			MongoDriverSettings.builder()
//				.database(prefix + "_slow")
//				.experimental(resilient3)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(200)
//					.build()),
//			MongoDriverSettings.builder()
//				.database(prefix + "_fast")
//				.experimental(resilient3)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(-200)
//					.build())
		);
	}

}
