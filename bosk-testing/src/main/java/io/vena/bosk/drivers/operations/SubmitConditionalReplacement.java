package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;

public record SubmitConditionalReplacement<T>(
	Reference<T> target,
	T newValue,
	Reference<Identifier> precondition,
	Identifier requiredValue,
	MapValue<String> diagnosticAttributes
) implements ReplacementOperation<T>, ConditionalOperation {

	@Override
	public SubmitReplacement<T> unconditional() {
		return new SubmitReplacement<>(target, newValue, diagnosticAttributes);
	}

	@Override
	public SubmitConditionalReplacement<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

}
