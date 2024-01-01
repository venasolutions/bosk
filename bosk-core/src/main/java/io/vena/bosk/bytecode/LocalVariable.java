package io.vena.bosk.bytecode;

import org.objectweb.asm.Type;

public record LocalVariable(
	Type type,
	int slot
) { }
