package io.vena.bosk.drivers.mongo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class TestParameters {
	private static final AtomicInteger dbCounter = new AtomicInteger(0);

	@SuppressWarnings("unused")
	static Stream<MongoDriverSettings.MongoDriverSettingsBuilder> driverSettings(
		Stream<MongoDriverSettings.DatabaseFormat> formats,
		Stream<EarlyOrLate> timings
	) {
		List<EarlyOrLate> timingsList = timings.collect(toList());
		return formats
			.flatMap(f -> timingsList.stream()
				.map(e -> MongoDriverSettings.builder()
					.preferredDatabaseFormat(f)
					.recoveryPollingMS(3000) // Note that some tests can take as long as 10x this
					.flushTimeoutMS(4000) // A little more than recoveryPollingMS
					.testing(MongoDriverSettings.Testing.builder().eventDelayMS(e.eventDelayMS).build())
					.database(MongoDriverResiliencyTest.class.getSimpleName()
						+ "_" + dbCounter.incrementAndGet()
						+ "_" + f.getClass().getSimpleName()
						+ e.suffix)
				));
	}

	enum EarlyOrLate {
		NORMAL(0, ""),
		EARLY(-200, "_early"),
		LATE(200, "_late");

		final int eventDelayMS;
		final String suffix;

		EarlyOrLate(int eventDelayMS, String suffix) {
			this.eventDelayMS = eventDelayMS;
			this.suffix = suffix;
		}
	}

}
