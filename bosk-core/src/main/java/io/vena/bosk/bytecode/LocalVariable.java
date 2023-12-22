package io.vena.bosk.bytecode;

import lombok.Value;
import org.objectweb.asm.Type;

@Value
public class LocalVariable {
	Type type;
	int slot;
}
