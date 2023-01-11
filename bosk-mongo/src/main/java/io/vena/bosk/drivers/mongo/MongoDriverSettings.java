package io.vena.bosk.drivers.mongo;

import io.vena.bosk.BoskDriver;
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
	@Default FlushMode flushMode = FlushMode.REVISION_FIELD;
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

	public enum FlushMode {
		/**
		 * The canonical implementation of {@link BoskDriver#flush()}: performs a dummy
		 * write to the database, and waits for the corresponding event to arrive in the
		 * MongoDB change stream, thereby ensuring that all prior events have already
		 * been processed.
		 *
		 * <p>
		 * Since this mode performs a write, it needs write permissions to the database,
		 * and causes change stream activity even when the bosk state is not changing.
		 */
		ECHO,

		/**
		 * Reads a {@link io.vena.bosk.drivers.mongo.Formatter.DocumentFields#revision revision field}
		 * in the database; if we have not yet processed that revision, wait until we have.
		 *
		 * <p>
		 * This is more sophisticated and subtle than {@link #ECHO}, but doesn't perform any writes.
		 * When the bosk is not changing, this doesn't need to wait for any change stream events,
		 * and runs as quickly as a single database read.
		 */
		REVISION_FIELD,
	}
}
