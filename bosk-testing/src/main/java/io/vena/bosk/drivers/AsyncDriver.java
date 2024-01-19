package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static lombok.AccessLevel.PRIVATE;

@RequiredArgsConstructor(access = PRIVATE)
public class AsyncDriver<R extends StateTreeNode> implements BoskDriver<R> {
	private final Bosk<R> bosk;
	private final BoskDriver<R> downstream;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	public static <RR extends StateTreeNode> DriverFactory<RR> factory() {
		return AsyncDriver::new;
	}

	@Override
	public R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException {
		return downstream.initialRoot(rootType);
	}

	@Override
	public <T> void submitReplacement(Reference<T> target, T newValue) {
		submitAsyncTask("submitReplacement", () -> downstream.submitReplacement(target, newValue));
	}

	@Override
	public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
		submitAsyncTask("submitConditionalReplacement", () -> downstream.submitConditionalReplacement(target, newValue, precondition, requiredValue));
	}

	@Override
	public <T> void submitInitialization(Reference<T> target, T newValue) {
		submitAsyncTask("submitInitialization", () -> downstream.submitInitialization(target, newValue));
	}

	@Override
	public <T> void submitDeletion(Reference<T> target) {
		submitAsyncTask("submitDeletion", () -> downstream.submitDeletion(target));
	}

	@Override
	public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
		submitAsyncTask("submitConditionalDeletion", () -> downstream.submitConditionalDeletion(target, precondition, requiredValue));
	}

	@Override
	public void flush() throws IOException, InterruptedException {
		Semaphore semaphore = new Semaphore(0);
		submitAsyncTask("flush", semaphore::release);
		semaphore.acquire();
		downstream.flush();
	}

	private void submitAsyncTask(String description, Runnable task) {
		LOGGER.debug("Submit {}", description);
		var diagnosticAttributes = bosk.diagnosticContext().getAttributes();
		executor.submit(()->{
			LOGGER.debug("Run {}", description);
			try (var __ = bosk.diagnosticContext().withOnly(diagnosticAttributes)) {
				task.run();
			}
			LOGGER.trace("Done {}", description);
		});
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(AsyncDriver.class);
}
