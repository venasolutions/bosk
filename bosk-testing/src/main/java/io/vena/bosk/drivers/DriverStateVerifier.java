package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import io.vena.bosk.BoskDriver;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.DriverStack;
import io.vena.bosk.Reference;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.drivers.operations.UpdateOperation;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NotYetImplementedException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.Thread.currentThread;
import static lombok.AccessLevel.PRIVATE;

/**
 * Watches the updates entering and leaving a particular {@link BoskDriver} and ensures
 * that they have the same effect on the bosk state. If a mismatch is found, throws
 * {@link AssertionError}.
 *
 * <p>
 * Note: this verifier uses {@link Object#equals} to compare parts of the state tree,
 * expecting value-based equality. If used with state tree nodes having different equality
 * semantics, the resulting verifier could be more or less strict than expected.
 */
@RequiredArgsConstructor(access = PRIVATE)
public class DriverStateVerifier<R extends StateTreeNode> {
	/**
	 * Used to model the effect of each operation on the bosk state
	 */
	final Bosk<R> stateTrackingBosk;

	/**
	 * Unlike {@link #stateTrackingBosk}.{@link Bosk#driver() driver()},
	 * this driver can accept updates with references pointing to a different bosk with the same root type.
	 */
	final BoskDriver<R> stateTrackingDriver;

	final Map<String, Deque<UpdateOperation>> pendingOperationsByThreadName = new ConcurrentHashMap<>();
	static final String THREAD_NAME = "thread.name";

	public static <RR extends StateTreeNode> DriverFactory<RR> wrap(DriverFactory<RR> subject, Type rootType, Bosk.DefaultRootFunction<RR> defaultRootFunction) {
		Bosk<RR> stateTrackingBosk = new Bosk<>(
			"Tracking",
			rootType, defaultRootFunction,
			Bosk::simpleDriver
		);
		DriverStateVerifier<RR> verifier = new DriverStateVerifier<>(
			stateTrackingBosk,
			MirroringDriver.redirectingTo(stateTrackingBosk)
		);
		return DriverStack.of(
			DiagnosticScopeDriver.factory(dc -> dc.withAttribute(THREAD_NAME, currentThread().getName())),
			ReportingDriver.factory(verifier::incomingUpdate, verifier::incomingFlush),
			subject,
			ReportingDriver.factory(verifier::outgoingUpdate, verifier::outgoingFlush)
		);
	}

	/**
	 * Called when an update is about to be sent to the subject driver.
	 * Thread-safe and non-blocking.
	 */
	private void incomingUpdate(UpdateOperation updateOperation) {
		LOGGER.debug("---> IN: {}", updateOperation);
		// Note: because we have a separate queue for each thread, this isn't actually blocking
		pendingOperationsByThreadName
			.computeIfAbsent(updateOperation.diagnosticAttributes().get(THREAD_NAME), t -> new LinkedBlockingDeque<>())
			.addLast(updateOperation);
	}

	private void incomingFlush() {
		LOGGER.debug("incomingFlush()");
	}

	/**
	 * Called when an update has been sent downstream from the subject driver.
	 * Synchronized not only to protect the integrity of data structures,
	 * but also to establish the canonical order in which updates are applied.
	 */
	private synchronized void outgoingUpdate(UpdateOperation op) {
		LOGGER.debug("---> OUT: {}", op);
		try {
			Object before = currentStateBefore(op);
			Object after = hypotheticalStateAfter(op);
			LOGGER.trace("\t\tbefore: {}", before);
			LOGGER.trace("\t\t after: {}", after);

			String threadName = op.diagnosticAttributes().get(THREAD_NAME);
			if (threadName == null) {
				LOGGER.debug("\tMissing " + THREAD_NAME + " diagnostic attribute");
			} else {
				Deque<UpdateOperation> q = pendingOperationsByThreadName.get(threadName);
				if (q == null) {
					LOGGER.debug("\tNo queued events for thread \"{}\"", threadName);
				} else {
					LOGGER.trace("\tThread \"{}\" has {} queued operations", threadName, q.size());
					for (UpdateOperation expected : q) {
						Object expectedBefore = currentStateBefore(expected); // May not equal `before` if the two operations have different targets
						Object expectedAfter = hypotheticalStateAfter(expected);
						LOGGER.trace("\t\texpectedAfter: {}", expectedAfter);
						if (op.matchesIfApplied(expected) && Objects.equals(after, expectedAfter)) {
							LOGGER.debug("\tConclusion: found match: {}", expected);
							UpdateOperation discarded;
							while ((discarded = q.removeFirst()) != expected) {
								LOGGER.trace("\t\tdiscard preceding no-op: {}", discarded);
							}
							expected.submitTo(stateTrackingDriver);
							return;
						} else if (Objects.equals(expectedBefore, expectedAfter)) {
							LOGGER.trace("\t\tSkip queued no-op: {}", expected);
						} else {
							LOGGER.trace("\t\tNo match for: {}", expected);
							break;
						}
					}
				}
			}

			if (Objects.equals(before, after)) {
				LOGGER.debug("\tConclusion: spontaneous no-op: {}", op);
			} else {
				throw new AssertionError("No matching operation\n\t" + op);
			}
		} catch (IOException | InterruptedException e) {
			throw new NotYetImplementedException(e);
		}
	}

	private void outgoingFlush() {
		LOGGER.debug("outgoingFlush()");
		pendingOperationsByThreadName.forEach((thread, q) -> {
			if (!q.isEmpty()) {
				throw new AssertionError(q.size() + " pending operations remain on thread " + thread
					+ "\n\tFirst is: " + q.getFirst());
			}
		});
	}

	@SuppressWarnings("unchecked")
	private <T> T currentStateBefore(UpdateOperation op) throws IOException, InterruptedException {
		Reference<T> stateTrackingRef = (Reference<T>) stateTrackingRef(op.target());
		stateTrackingBosk.driver().flush();
		try (var __ = stateTrackingBosk.readContext()) {
			return stateTrackingRef.valueIfExists();
		}
	}

	@SuppressWarnings("unchecked")
	private <T> T hypotheticalStateAfter(UpdateOperation op) throws IOException, InterruptedException {
		T before;
		R originalState;
		Reference<T> stateTrackingRef = (Reference<T>) stateTrackingRef(op.target());
		stateTrackingBosk.driver().flush();
		try (var __ = stateTrackingBosk.readContext()) {
			originalState = stateTrackingBosk.rootReference().value();
			before = stateTrackingRef.valueIfExists();
		}
		op.submitTo(stateTrackingDriver);
		stateTrackingBosk.driver().flush();
		try (var __ = stateTrackingBosk.readContext()) {
			return stateTrackingRef.valueIfExists();
		} finally {
			stateTrackingBosk.driver().submitReplacement(stateTrackingBosk.rootReference(), originalState);
		}
	}

	@SuppressWarnings("unchecked")
	private <T> Reference<T> stateTrackingRef(Reference<T> original) {
		try {
			return (Reference<T>) stateTrackingBosk.rootReference().then(Object.class, original.path());
		} catch (InvalidTypeException e) {
			throw new AssertionError("References are expected to be compatible: " + original, e);
		}
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DriverStateVerifier.class);
}
