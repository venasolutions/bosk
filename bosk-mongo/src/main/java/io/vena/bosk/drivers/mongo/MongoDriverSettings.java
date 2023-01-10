package io.vena.bosk.drivers.mongo;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
@Builder
public class MongoDriverSettings {
	String database;

	@Default long flushTimeoutMS = 30_000;
	@Default Testing testing = Testing.builder().build();

	@Value
	@Accessors(fluent = true)
	@Builder
	public static class Testing {
		/**
		 * How long to sleep before processing each event.
		 * If negative, sleeps before performing each database update.
		 */
		@Default long eventDelayMS = 0;
	}
}
