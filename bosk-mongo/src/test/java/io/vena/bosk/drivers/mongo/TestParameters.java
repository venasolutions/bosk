package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public interface TestParameters {
	AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		String prefix = "boskTestDB_" + dbCounter.incrementAndGet();
		Experimental resilient = Experimental.builder()
			.build();
		return Stream.of(
			MongoDriverSettings.builder()
				.database(prefix)
				.experimental(resilient)
//			MongoDriverSettings.builder()
//				.database(prefix + "_slow")
//				.experimental(resilient)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(200)
//					.build()),
//			MongoDriverSettings.builder()
//				.database(prefix + "_fast")
//				.experimental(resilient)
//				.testing(MongoDriverSettings.Testing.builder()
//					.eventDelayMS(-200)
//					.build())
		);
	}

}
