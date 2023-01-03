package io.vena.bosk.bytecode;

import lombok.Value;

@Value
public class CurriedField {
	int slot; // The parameter slot in which this will arrive in the constructor
	String name;
	String typeDescriptor;
	Object value;
}
