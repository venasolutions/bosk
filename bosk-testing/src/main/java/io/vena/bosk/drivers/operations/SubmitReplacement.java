package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;

public record SubmitReplacement<T>(
	Reference<T> target,
	T newValue,
	MapValue<String> diagnosticAttributes
) implements ReplacementOperation<T> {

	@Override
	public SubmitReplacement<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitReplacement<>(target, newValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitReplacement(target, newValue);
	}

}
