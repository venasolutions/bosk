package io.vena.bosk.drivers.mongo.example;

import com.mongodb.MongoClientSettings;
import io.vena.bosk.Bosk;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.BsonPlugin;
import io.vena.bosk.drivers.mongo.MongoDriver;
import io.vena.bosk.drivers.mongo.MongoDriverSettings;
import io.vena.bosk.exceptions.InvalidTypeException;

public class ExampleBosk extends Bosk<ExampleState> {
	public ExampleBosk() throws InvalidTypeException {
		super(
			"ExampleBosk",
			ExampleState.class,
			defaultRoot(),
			driverFactory());
	}

	public interface Refs {
		// Typically, you add a bunch of useful references here, like this one:
		@ReferencePath("/name") Reference<String> name();
	}

	public final Refs refs = rootReference().buildReferences(Refs.class);

	private static ExampleState defaultRoot() {
		return new ExampleState(Identifier.from("example"), "world");
	}

	private static DriverFactory<ExampleState> driverFactory() {
		MongoClientSettings clientSettings = MongoClientSettings.builder()
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
