package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import lombok.Value;

@Value
public class SubmitConditionalDeletion<T> implements DeletionOperation<T>, ConditionalOperation {
	Reference<T> target;
	Reference<Identifier> precondition;
	Identifier requiredValue;

	@Override
	public SubmitDeletion<T> unconditional() {
		return new SubmitDeletion<>(target);
	}

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitConditionalDeletion(target, precondition, requiredValue);
	}
}
