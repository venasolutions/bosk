package io.vena.bosk.drivers.operations;

import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;

/**
 * Doesn't include {@link SubmitInitialization} because that has a different kind of precondition.
 */
public sealed interface ConditionalOperation extends UpdateOperation permits
	SubmitConditionalDeletion,
	SubmitConditionalReplacement
{
	Reference<Identifier> precondition();
	Identifier requiredValue();
	UpdateOperation unconditional();
}
