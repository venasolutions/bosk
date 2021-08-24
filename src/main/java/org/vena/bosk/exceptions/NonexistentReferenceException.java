package org.vena.bosk.exceptions;

import org.vena.bosk.Listing;
import org.vena.bosk.Reference;

/**
 * Thrown when {@link Reference#value()} is called and the referenced object does not exist.
 * Also thrown by analogous methods like {@link Listing#getValue}.
 */
@SuppressWarnings("serial")
public class NonexistentReferenceException extends RuntimeException {
	final Reference<?> reference;

	public NonexistentReferenceException(Reference<?> reference) {
		super(message(reference));
		this.reference = reference;
	}

	public NonexistentReferenceException(Reference<?> reference, Throwable cause) {
		super(message(reference), cause);
		this.reference = reference;
	}

	private static String message(Reference<?> reference) {
		return "Reference to nonexistent " + reference.targetClass().getSimpleName() + ": \"" + reference.path() + "\"";
	}

}
