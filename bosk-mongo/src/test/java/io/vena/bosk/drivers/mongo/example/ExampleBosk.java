package io.vena.bosk.drivers.mongo.example;

import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.WriteConcern;
import io.vena.bosk.Bosk;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.exceptions.InvalidTypeException;

public class ExampleBosk extends Bosk<ExampleState> {
	public ExampleBosk() throws InvalidTypeException {
		super(
			"ExampleBosk",
			ExampleState.class,
			new ExampleState(Identifier.from("example"), "world"),
			driverFactory());
	}

	// Typically, you add a bunch of useful references here, like this one:
	public final Reference<String> nameRef = reference(String.class, Path.parse("/name"));

	private static DriverFactory<ExampleState> driverFactory() {
		MongoClientSettings clientSettings = MongoClientSettings.builder()
			.readConcern(ReadConcern.MAJORITY)
			.writeConcern(WriteConcern.MAJORITY)
			.build();

		MongoDriverSettings driverSettings = MongoDriverSettings.builder()
			.database("exampleDB")
			.build();

		// For advanced usage, you'll want to inject this object,
		// but for getting started, we can just create one here.
		BsonPlugin bsonPlugin = new BsonPlugin();

		return MongoDriver.factory(
			clientSettings,
			driverSettings,
			bsonPlugin);
	}
}
