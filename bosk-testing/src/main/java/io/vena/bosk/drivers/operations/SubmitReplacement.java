package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import lombok.Value;

@Value
public class SubmitReplacement<T> implements ReplacementOperation<T> {
	Reference<T> target;
	T newValue;
	MapValue<String> diagnosticAttributes;

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitReplacement(target, newValue);
	}
}
