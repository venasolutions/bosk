package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Reference;
import lombok.Value;

@Value
public class SubmitDeletion<T> implements DeletionOperation<T> {
	Reference<T> target;

	@Override
	public void submitTo(BoskDriver<?> driver) {
		driver.submitDeletion(target);
	}
}
