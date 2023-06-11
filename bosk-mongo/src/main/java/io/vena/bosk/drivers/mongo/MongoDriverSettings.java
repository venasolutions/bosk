package io.vena.bosk.drivers.mongo;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.drivers.mongo.Formatter.DocumentFields;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

@Value
@Builder
public class MongoDriverSettings {
	String database;

	@Default long flushTimeoutMS = 30_000;
	@Default DatabaseFormat preferredDatabaseFormat = DatabaseFormat.SINGLE_DOC;

	@Default Experimental experimental = Experimental.builder().build();
	@Default Testing testing = Testing.builder().build();

	/**
	 * Settings with no guarantee of long-term support.
	 */
	@Value
	@Builder
	public static class Experimental {
		@Default ImplementationKind implementationKind = ImplementationKind.STABLE;
		@Default FlushMode flushMode = FlushMode.ECHO;
		@Default long changeStreamInitialWaitMS = 20;
	}

	/**
	 * Settings not meant to be used in production.
	 */
	@Value
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
		 * <strong>Experimental</strong>
		 *
		 * <p>
		 * Reads the {@link DocumentFields#revision revision field} in the database;
		 * if we have not yet processed that revision, wait until we have.
		 *
		 * <p>
		 * This implementation is more complex and subtle than {@link #ECHO},
		 * but doesn't perform any writes.
		 * When the bosk is not changing, this doesn't need to wait for any change stream events,
		 * and runs as quickly as a single database read.
		 */
		REVISION_FIELD_ONLY,
	}

	public enum ImplementationKind {
		/**
		 * The more mature, well-tested implementation.
		 */
		STABLE,

		/**
		 * <strong>Experimental</strong>
		 *
		 * <p>
		 * A newer implementation with better resiliency features.
		 * Ignores {@link FlushMode FlushMode}; only supports the equivalent of {@link FlushMode#REVISION_FIELD_ONLY REVISION_FIELD_ONLY}.
		 */
		RESILIENT,
	}

	public enum DatabaseFormat {
		SINGLE_DOC
	}
}
