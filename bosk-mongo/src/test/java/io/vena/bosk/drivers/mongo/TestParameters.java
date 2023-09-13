package io.vena.bosk.drivers.mongo;

import io.vena.bosk.drivers.mongo.MongoDriverSettings.MongoDriverSettingsBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.Arrays.asList;

public interface TestParameters {
	AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettingsBuilder> driverSettings() {
		String prefix = "boskTestDB_" + dbCounter.incrementAndGet();
		return Stream.of(
			MongoDriverSettings.builder()
				.database(prefix + "_default"),
			MongoDriverSettings.builder()
				.database(prefix + "_pando1")
				.preferredDatabaseFormat(PandoFormat.oneBigDocument()),
			MongoDriverSettings.builder()
				.database(prefix + "_pando2")
				.preferredDatabaseFormat(PandoFormat.withSeparateCollections(asList(
					"/catalog", "/sideTable"))),
			MongoDriverSettings.builder()
				.database(prefix + "_slow")
				.testing(MongoDriverSettings.Testing.builder()
					.eventDelayMS(200)
					.build()),
			MongoDriverSettings.builder()
				.database(prefix + "_fast")
				.testing(MongoDriverSettings.Testing.builder()
					.eventDelayMS(-200)
					.build())
		);
	}

}
