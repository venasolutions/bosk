package io.vena.bosk.drivers.mongo;

import com.mongodb.MongoClientSettings;
import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;

public interface MongoDriver<R extends Entity> extends BoskDriver<R> {
	void refurbish();
	void close();

	static <RR extends Entity> MongoDriverFactory<RR> factory(MongoClientSettings clientSettings, MongoDriverSettings driverSettings, BsonPlugin bsonPlugin) {
		return (b, d) -> new SingleDocumentMongoDriver<>(b, clientSettings, driverSettings, bsonPlugin, d);
	}

	interface MongoDriverFactory<RR extends Entity> extends DriverFactory<RR> {
		@Override MongoDriver<RR> build(Bosk<RR> bosk, BoskDriver<RR> downstream);
	}
}
