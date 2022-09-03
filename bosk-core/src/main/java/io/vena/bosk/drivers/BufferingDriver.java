package io.vena.bosk.drivers;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

/**
 * Queues updates and submits them to a downstream driver when {@link #flush()}
 * is called.
 *
 * <p>
 * This has the effect of causing the whole list of updates to be
 * discarded if an exception is thrown while the updates are being computed,
 * which could be a desirable property. However, the buffered updates are
 * <strong>not</strong> submitted downstream atomically: other updates from other
 * threads may be interleaved. (They are, of course, submitted downstream
 * in the order they were submitted to this driver.)
 *
 * @author pdoyle
 */
@RequiredArgsConstructor(access = PROTECTED)
public class BufferingDriver<R extends Entity> implements BoskDriver<R> {
	private final BoskDriver<R> downstream;
	private final Deque<Consumer<BoskDriver<R>>> updateQueue = new ConcurrentLinkedDeque<>();

	public static <RR extends Entity> BufferingDriver<RR> writingTo(BoskDriver<RR> downstream) {
		return new BufferingDriver<>(downstream);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		updateQueue.add(d -> d.submitReplacement(target, newValue));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		updateQueue.add(d -> d.submitInitialization(target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		updateQueue.add(d -> d.submitDeletion(target));
	}

	@Override
	public void flush() throws InterruptedException, IOException {
		for (Consumer<BoskDriver<R>> update = updateQueue.pollFirst(); update != null; update = updateQueue.pollFirst()) {
			update.accept(downstream);
		}
		downstream.flush();
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		updateQueue.add(d -> d.submitConditionalReplacement(target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		updateQueue.add(d -> d.submitConditionalDeletion(target, precondition, requiredValue));
	}

}
