package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;

public record SubmitInitialization<T>(
	Reference<T> target,
	T newValue,
	MapValue<String> diagnosticAttributes
) implements ReplacementOperation<T> {

	@Override
	public SubmitInitialization<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitInitialization<>(target, newValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitInitialization(target, newValue);
	}

}
