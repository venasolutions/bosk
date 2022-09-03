package io.vena.bosk.bytecode;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class CurriedField {
	int slot; // The parameter slot in which this will arrive in the constructor
	String name;
	String typeDescriptor;
	Object value;
}
