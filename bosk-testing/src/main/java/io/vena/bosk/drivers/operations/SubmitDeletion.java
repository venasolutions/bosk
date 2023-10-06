package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;
import lombok.Value;

@Value
public class SubmitDeletion<T> implements DeletionOperation<T> {
	Reference<T> target;
	MapValue<String> diagnosticAttributes;

	@Override
	public SubmitDeletion<T> withFilteredAttributes(Collection<String> allowedNames) {
		return new SubmitDeletion<>(target, MapValue.fromFunction(allowedNames, diagnosticAttributes::get));
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitDeletion(target);
	}
}
