package io.vena.bosk.drivers.operations;

import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;

/**
 * Doesn't include {@link SubmitInitialization}.
 */
public interface ConditionalOperation extends UpdateOperation {
	Reference<Identifier> precondition();
	Identifier requiredValue();
	UpdateOperation unconditional();
}
