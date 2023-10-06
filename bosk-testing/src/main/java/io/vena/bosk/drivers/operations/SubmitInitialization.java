package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;
import lombok.Value;

@Value
public class SubmitInitialization<T> implements ReplacementOperation<T> {
	Reference<T> target;
	T newValue;
	MapValue<String> diagnosticAttributes;

	@Override
	public SubmitInitialization<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitInitialization<>(target, newValue, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitInitialization(target, newValue);
	}
}
