package org.vena.bosk.bytecode;

import lombok.Value;
import lombok.experimental.Accessors;

@Value
@Accessors(fluent = true)
public class LocalVariable {
	int slot;
}
