package org.vena.bosk.drivers;

import java.io.IOException;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Reference;
import org.vena.bosk.exceptions.InvalidTypeException;

/**
 * Sends events to another {@link Bosk} of the same type.
 */
@RequiredArgsConstructor
public class MirroringDriver<R extends Entity> implements BoskDriver<R> {
	private final Bosk<R> mirror;

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException {
		throw new UnsupportedOperationException(MirroringDriver.class.getSimpleName() + " cannot supply an initial root");
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		mirror.driver().submitReplacement(correspondingReference(target), newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		mirror.driver().submitConditionalReplacement(correspondingReference(target), newValue, correspondingReference(precondition), requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		mirror.driver().submitInitialization(correspondingReference(target), newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		mirror.driver().submitDeletion(correspondingReference(target));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		mirror.driver().submitConditionalDeletion(correspondingReference(target), correspondingReference(precondition), requiredValue);
	}

	@Override
	public void flush() throws InterruptedException, IOException {
		mirror.driver().flush();
	}

	@SuppressWarnings("unchecked")
	private <T> Reference<T> correspondingReference(Reference<T> original) {
		try {
			return (Reference<T>)mirror.reference(Object.class, original.path());
		} catch (InvalidTypeException e) {
			throw new AssertionError("References are expected to be compatible: " + original, e);
		}
	}

}
