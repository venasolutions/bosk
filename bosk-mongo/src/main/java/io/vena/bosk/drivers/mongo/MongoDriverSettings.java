package io.vena.bosk.drivers.mongo;

import io.vena.bosk.BoskDriver;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Value;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode.USE_IF_EXISTS;
import static io.vena.bosk.drivers.mongo.MongoDriverSettings.OrphanDocumentMode.EARNEST;

@Value
@Builder(toBuilder = true)
public class MongoDriverSettings {
	String database;

	@Default long flushTimeoutMS = 30_000;
	@Default long recoveryPollingMS = 30_000;
	/**
	 * @see DatabaseFormat#SEQUOIA
	 * @see PandoFormat
	 */
	@Default DatabaseFormat preferredDatabaseFormat = SEQUOIA;
	@Default InitialDatabaseUnavailableMode initialDatabaseUnavailableMode = InitialDatabaseUnavailableMode.DISCONNECT;

	@Default Experimental experimental = Experimental.builder().build();
	@Default Testing testing = Testing.builder().build();

	/**
	 * Settings with no guarantee of long-term support.
	 */
	@Value
	@Builder
	public static class Experimental {
		@Default long changeStreamInitialWaitMS = 20;
		@Default ManifestMode manifestMode = ManifestMode.CREATE_IF_ABSENT;
		@Default OrphanDocumentMode orphanDocumentMode = OrphanDocumentMode.HASTY;
	}

	/**
	 * Settings not meant to be used in production.
	 */
	@Value
	@Builder
	public static class Testing {
		/**
		 * How long to sleep before processing each event.
		 * If negative, sleeps before performing each driver operation
		 * so that events have a chance to arrive first.
		 */
		@Default long eventDelayMS = 0;
	}

	public interface DatabaseFormat {
		/**
		 * Simple format that stores the entire bosk state in a single document,
		 * and (except for {@link MongoDriver#refurbish() refirbish})
		 * doesn't require any multi-document transactions.
		 * <p>
		 * This limits the entire bosk state to 16MB when converted to BSON.
		 */
		DatabaseFormat SEQUOIA = new SequoiaFormat();
	}

	private static final class SequoiaFormat implements DatabaseFormat {
		@Override public String toString() { return "SequoiaFormat"; }
	}

	public enum InitialDatabaseUnavailableMode {
		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialRoot},
		 * use the downstream driver's initial state and proceed in disconnected mode.
		 * This allows the database and application to be booted in either order,
		 * which can simplify repairs and recovery in production,
		 * but during development, it can cause confusing behaviour if the database is misconfigured.
		 * <p>
		 * In the spirit of making things "just work in production", this is the default,
		 * but you might want to consider using {@link #FAIL} in non-production settings.
		 */
		DISCONNECT,

		/**
		 * If the database state can't be loaded during {@link BoskDriver#initialRoot},
		 * throw an exception.
		 * This is probably the desired "fail fast" behaviour during development,
		 * but in production, it creates a boot sequencing dependency between the application and the database.
		 */
		FAIL
	}

	public enum ManifestMode {
		/**
		 * If a manifest document doesn't exist, we'll assume certain defaults.
		 */
		USE_IF_EXISTS,

		/**
		 * If a manifest document doesn't exist, we'll create one.
		 */
		CREATE_IF_ABSENT,
	}

	public enum OrphanDocumentMode {
		/**
		 * Unused documents are always deleted before the end of the transaction.
		 */
		EARNEST,

		/**
		 * Unused documents may be left behind, to be cleaned up later.
		 */
		HASTY,
	}

	public void validate() {
		if (preferredDatabaseFormat() instanceof PandoFormat) {
			if (experimental.manifestMode() == USE_IF_EXISTS) {
				throw new IllegalArgumentException("Pando format requires a manifest. Databases with no manifest are interpreted as Sequoia.");
			}
			if (experimental.orphanDocumentMode() == EARNEST) {
				throw new IllegalArgumentException("Pando format does not support earnest orphan document cleanup");
			}
		}
	}

}
