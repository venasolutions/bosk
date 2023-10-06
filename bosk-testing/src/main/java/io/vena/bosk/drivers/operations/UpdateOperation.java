package io.vena.bosk.drivers.operations;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.MapValue;
import io.vena.bosk.Reference;
import java.util.Collection;

public interface UpdateOperation {
	Reference<?> target();
	MapValue<String> diagnosticAttributes();

	/**
	 * @return true if this operation matches <code>other</code> ignoring any preconditions.
	 */
	boolean matchesIfApplied(UpdateOperation other);

	UpdateOperation withFilteredAttributes(Collection<String> allowedNames);
	/**
	 * Calls the appropriate <code>submit</code> method on the given driver.
	 * Any {@link io.vena.bosk.BoskDiagnosticContext diagnostic context} is <em>not</em> propagated;
	 * if that behaviour is desired, the caller must do it.
	 */
	void submitTo(BoskDriver<?> driver);
}
