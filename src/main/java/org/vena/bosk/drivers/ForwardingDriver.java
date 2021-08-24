package org.vena.bosk.drivers;

import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Entity;
import org.vena.bosk.Reference;
import org.vena.bosk.exceptions.InvalidTypeException;

@RequiredArgsConstructor
public class ForwardingDriver<R extends Entity> implements BoskDriver<R> {
	private final Iterable<BoskDriver<R>> downstream;

	/**
	 * @return The result of calling <code>initialRoot</code> on the first downstream driver
	 * that doesn't throw {@link UnsupportedOperationException}.
	 */
	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException {
		UnsupportedOperationException lastExceptionThrown = null;
		for (BoskDriver<R> d: downstream) {
			try {
				return d.initialRoot(rootType);
			} catch (UnsupportedOperationException e) {
				lastExceptionThrown = e;
			}
		}

		// Oh dear.
		throw new UnsupportedOperationException("Unable to forward initialRoot request", lastExceptionThrown);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		downstream.forEach(d -> d.submitReplacement(target, newValue));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<String> precondition, String requiredValue) {
		downstream.forEach(d -> d.submitConditionalReplacement(target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		downstream.forEach(d -> d.submitInitialization(target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		downstream.forEach(d -> d.submitDeletion(target));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<String> precondition, String requiredValue) {
		downstream.forEach(d -> d.submitConditionalDeletion(target, precondition, requiredValue));
	}

	@Override
	public void flush() throws InterruptedException {
		AtomicReference<InterruptedException> firstInterruption = new AtomicReference<>();
		downstream.forEach(d -> {
			try {
				d.flush();
			} catch (InterruptedException e) {
				firstInterruption.compareAndSet(null, e);
			}
		});
		InterruptedException toThrow = firstInterruption.get();
		if (toThrow != null) {
			throw toThrow;
		}
	}
}
