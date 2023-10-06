package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;
import lombok.Value;

@Value
public class SubmitConditionalDeletion<T> implements DeletionOperation<T>, ConditionalOperation {
	Reference<T> target;
	Reference<Identifier> precondition;
	Identifier requiredValue;
	MapValue<String> diagnosticAttributes;

	@Override
	public SubmitDeletion<T> unconditional() {
		return new SubmitDeletion<>(target, diagnosticAttributes);
	}

	@Override
	public SubmitConditionalDeletion<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitConditionalDeletion<>(target, precondition, requiredValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitConditionalDeletion(target, precondition, requiredValue);
	}
}
