package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Reference;

public interface UpdateOperation {
	Reference<?> target();
	boolean matchesIfApplied(UpdateOperation other);
	void submitTo(BoskDriver<?> driver);
}
