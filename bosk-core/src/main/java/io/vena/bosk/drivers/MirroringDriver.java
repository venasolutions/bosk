package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;

import static java.util.Arrays.asList;
import static lombok.AccessLevel.PRIVATE;

/**
 * Sends events to another {@link Bosk} of the same type.
 */
@RequiredArgsConstructor(access=PRIVATE)
public class MirroringDriver<R extends Entity> implements BoskDriver<R> {
	private final Bosk<R> mirror;

	public static <RR extends Entity> DriverFactory<RR> targeting(Bosk<RR> mirror) {
		return (bosk, downstream) -> new ForwardingDriver<>(asList(
			new MirroringDriver<>(mirror),
			downstream
		));
	}

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

	@Override
	public String toString() {
		return "Mirroring to " + mirror;
	}
}
