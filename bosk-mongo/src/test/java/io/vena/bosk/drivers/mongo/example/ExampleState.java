package io.vena.bosk.drivers.mongo.example;

import io.vena.bosk.StateTreeNode;
import lombok.Value;

@Value
public class ExampleState implements StateTreeNode {
	// Add fields here as you need them
	String name;
}
