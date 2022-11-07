package io.vena.bosk.drivers.mongo.example;

import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class ExampleState implements Entity {
	Identifier id;
	String name;
}
