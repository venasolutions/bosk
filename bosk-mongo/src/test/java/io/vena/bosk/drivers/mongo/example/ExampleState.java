package io.vena.bosk.drivers.mongo.example;

import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import lombok.Value;

@Value
public class ExampleState implements Entity {
	Identifier id;
	// Add fields here as you need them
	String name;
}
