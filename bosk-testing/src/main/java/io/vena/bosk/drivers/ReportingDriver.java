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
 * <p>
 * <em>Implementation note</em>: this class calls the downstream driver using {@link UpdateOperation#submitTo}
 * so that the ordinary {@link DriverConformanceTest} suite also tests all the {@link UpdateOperation} objects.
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
		SubmitReplacement<T> op = new SubmitReplacement<>(target, newValue);
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalReplacement<T> op = new SubmitConditionalReplacement<>(target, newValue, precondition, requiredValue);
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		SubmitInitialization<T> op = new SubmitInitialization<>(target, newValue);
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		SubmitDeletion<T> op = new SubmitDeletion<>(target);
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		SubmitConditionalDeletion<T> op = new SubmitConditionalDeletion<>(target, precondition, requiredValue);
		updateListener.accept(op);
		op.submitTo(downstream);
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		downstream.flush();
	}
}
