package io.vena.bosk.drivers;

import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.operations.SubmitConditionalDeletion;
import io.vena.bosk.drivers.operations.SubmitConditionalReplacement;
import io.vena.bosk.drivers.operations.SubmitDeletion;
import io.vena.bosk.drivers.operations.SubmitInitialization;
import io.vena.bosk.drivers.operations.SubmitReplacement;
import io.vena.bosk.drivers.operations.UpdateOperation;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.function.Consumer;

/**
 * Sends an {@link UpdateOperation} to a given listener whenever one of the update methods is called.
 */
public class ReportingDriver<R extends StateTreeNode> implements BoskDriver<R> {
	final BoskDriver<R> downstream;
	final Consumer<UpdateOperation> updateListener;
	final Runnable flushListener;

	private ReportingDriver(BoskDriver<R> downstream, Consumer<UpdateOperation> updateListener, Runnable flushListener) {
		this.downstream = downstream;
		this.updateListener = updateListener;
		this.flushListener = flushListener;
	}

	public static <RR extends StateTreeNode> DriverFactory<RR> factory(Consumer<UpdateOperation> listener, Runnable flushListener) {
		return (b,d) -> new ReportingDriver<>(d, listener, flushListener);
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		updateListener.accept(new SubmitReplacement<>(target, newValue));
		downstream.submitReplacement(target, newValue);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		updateListener.accept(new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue));
		downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		updateListener.accept(new SubmitInitialization<>(target, newValue));
		downstream.submitInitialization(target, newValue);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		updateListener.accept(new SubmitDeletion<>(target));
		downstream.submitDeletion(target);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		updateListener.accept(new SubmitConditionalDeletion<>(target, precondition, requiredValue));
		downstream.submitConditionalDeletion(target, precondition, requiredValue);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		downstream.flush();
	}
}
