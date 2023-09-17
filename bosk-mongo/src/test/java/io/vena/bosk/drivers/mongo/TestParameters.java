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
		Stream<EventTiming> timings
	) {
		List<EventTiming> timingsList = timings.collect(toList());
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

	enum EventTiming {
		NORMAL(0, ""),

		/**
		 * Updates are delayed to give events a chance to arrive first
		 */
		EARLY(-200, "_early"),

		/**
		 * Events are delayed to give other logic a chance to run first
		 */
		LATE(200, "_late");

		final int eventDelayMS;
		final String suffix;

		EventTiming(int eventDelayMS, String suffix) {
			this.eventDelayMS = eventDelayMS;
			this.suffix = suffix;
		}
	}

}
