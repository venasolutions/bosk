package io.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.mongo.status.MongoStatus;
import java.io.IOException;

/**
 * A {@link BoskDriver} that maintains the bosk state in a MongoDB database.
 * Multiple bosks, potentially in multiple separate processes,
 * can be configured to use the same database, thereby creating a replica set
 * with all bosks sharing the same state and receiving updates from each other.
 * <p>
 *
 * For convenience, if the database does not exist at the time of initialization,
 * this driver will create it and populate it with the state returned by calling
 * {@link BoskDriver#initialRoot} on the downstream driver.
 */
public sealed interface MongoDriver<R extends StateTreeNode>
	extends BoskDriver<R>
	permits MainDriver, FormatDriver {

	/**
	 * Deserializes and re-serializes the entire bosk contents,
	 * thus updating the database to match the current serialized format.
	 *
	 * <p>
	 * Used to "upgrade" the database contents for schema evolution.
	 *
	 * <p>
	 * This method does not simply write the current in-memory bosk contents
	 * back into the database, because that would lead to race conditions
	 * with other update operations.
	 * Instead, in a causally-consistent transaction, it reads the current
	 * database state, deserializes it, re-serializes it, and writes it back.
	 * This produces predictable results even if done concurrently with
	 * other database updates.
	 *
	 * <p>
	 * This requires the database state to be in good condition at the outset;
	 * it can't generally be used to repair corrupted databases
	 * unless the corruption is so mild that it doesn't
	 * interfere with proper functioning beforehand.
	 * It can be expected to evolve the database from that of a supported prior format,
	 * but for unsupported formats or other corruption, YMMV.
	 */
	void refurbish() throws IOException;

	MongoStatus readStatus() throws Exception;

	/**
	 * Frees up resources used by this driver and leaves it unusable.
	 *
	 * <p>
	 * This is done on a best-effort basis. It's more useful for tests than for production code,
	 * where there's usually no reason to close a driver.
	 */
	void close();

	static <RR extends StateTreeNode> MongoDriverFactory<RR> factory(
		MongoClientSettings clientSettings,
		MongoDriverSettings driverSettings,
		BsonPlugin bsonPlugin
	) {
		driverSettings.validate();
		return (b, d) -> new MainDriver<>(b, clientSettings, driverSettings, bsonPlugin, d);
	}

	interface MongoDriverFactory<RR extends StateTreeNode> extends DriverFactory<RR> {
		@Override MongoDriver<RR> build(Bosk<RR> bosk, BoskDriver<RR> downstream);
	}
}
