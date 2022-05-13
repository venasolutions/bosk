package org.vena.bosk;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vena.bosk.ReferenceUtils.CatalogRef;
import org.vena.bosk.ReferenceUtils.ListingRef;
import org.vena.bosk.ReferenceUtils.SideTableRef;
import org.vena.bosk.dereferencers.Dereferencer;
import org.vena.bosk.dereferencers.PathCompiler;
import org.vena.bosk.exceptions.AccessorThrewException;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NoReadContextException;
import org.vena.bosk.exceptions.NotYetImplementedException;
import org.vena.bosk.exceptions.ReferenceBindingException;
import org.vena.bosk.util.Classes;

import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static org.vena.bosk.Path.parameterNameFromSegment;
import static org.vena.bosk.ReferenceUtils.rawClass;
import static org.vena.bosk.TypeValidation.validateType;

/**
 * A mutable container for an immutable object tree with cross-tree {@link
 * Reference}s, providing snapshot-at-start semantics via {@link ReadContext}, and
 * managing updates via {@link BoskDriver}.
 *
 * <p>
 * The intent is that there would be one of these injected into your
 * application using something like Guice or Spring beans, and that you
 * manage your application's state this way using something like Martin
 * Fowler's Memory Image pattern, implementing a {@link
 * BoskDriver} that keeps the memory image up-to-date.
 *
 * <p>
 * Updates are performed by submitting an update via {@link
 * BoskDriver#submitReplacement(Reference, Object)} and similar,
 * rather than by modifying the in-memory state directly.  The driver
 * will apply the changes immediately or at a later time.  A special
 * "loopback" driver can be used for local development, applying
 * the changes in memory; or another driver could go through MongoDB
 * or similar to manage the document across multiple servers.
 *
 * <p>
 * Reads are performed by calling {@link Reference#value()} in the context of
 * a {@link ReadContext}.
 *
 * <p>
 * This class acts as a factory for {@link Reference} objects which can be used inside a
 * {@link ReadContext} to traverse the object trees by walking their fields
 * (actually getter methods) via a {@link Path}.
 *
 * <p>
 * This class makes heavy use of {@link Type}.  The
 * expectation is that these will be instances of {@link ParameterizedType}
 * unless the type in question is a non-generic class, in which case these will
 * be instances of {@link Class}.
 *
 * @author pdoyle
 *
 * @param <R> The type of the root {@link Entity}.
 */
@Accessors(fluent=true)
public class Bosk<R extends Entity> {
	@Getter private final String name;
	@Getter private final Identifier instanceID = Identifier.from(randomUUID().toString());
	@Getter private final BoskDriver<R> driver;
	private final LocalDriver localDriver;
	private final Type rootType;
	private final ThreadLocal<R> rootSnapshot = new ThreadLocal<>();
	private final List<HookRegistration<?>> hooks = new ArrayList<>();
	private final PathCompiler pathCompiler;

	// Mutable state
	private volatile R currentRoot;

	/**
	 * @param name Any string that identifies this object.
	 * @param rootType The @{link Type} of the root object.
	 * @param defaultRootFunction The root object to use if the driver chooses not to supply one,
	 *    and instead delegates {@link BoskDriver#initialRoot} all the way to the local driver.
	 *    Note that this function may or may not be called, so don't use it as a means to initialize
	 *    other state.
	 * @param driverFactory Will be applied to this Bosk's local driver during
	 * the Bosk's constructor, and the resulting {@link BoskDriver} will be the
	 * one to which updates will be submitted by {@link
	 * BoskDriver#submitReplacement(Reference, Object)} and {@link
	 * BoskDriver#submitDeletion(Reference)}.
	 */
	public Bosk(String name, Type rootType, DefaultRootFunction<R> defaultRootFunction, BiFunction<BoskDriver<R>, Bosk<R>, BoskDriver<R>> driverFactory) {
		this.name = name;
		this.localDriver = new LocalDriver(defaultRootFunction);
		this.rootType = rootType;
		this.pathCompiler = PathCompiler.withSourceType(rootType);
		try {
			validateType(rootType);
		} catch (InvalidTypeException e) {
			throw new IllegalArgumentException("Invalid root type " + rootType + ": " + e.getMessage(), e);
		}

		// We do this last because the driver factory is allowed to do such things
		// as create References, so it needs the rest of the initialization to
		// have completed already.
		this.driver = driverFactory.apply(this.localDriver, this);
		try {
			this.currentRoot = requireNonNull(driver.initialRoot(rootType));
		} catch (InvalidTypeException | IOException | InterruptedException e) {
			throw new IllegalArgumentException("Error computing initial root: " + e.getMessage(), e);
		}
		if (!rawClass(rootType).isInstance(this.currentRoot)) {
			throw new IllegalArgumentException("Initial root must be an instance of " + rawClass(rootType).getSimpleName());
		}
	}

	public interface DefaultRootFunction<RR extends Entity> {
		RR apply(Bosk<RR> bosk) throws InvalidTypeException;
	}

	public Bosk(String name, Type rootType, R defaultRoot, BiFunction<BoskDriver<R>, Bosk<R>, BoskDriver<R>> driverFactory) {
		this(name, rootType, b->defaultRoot, driverFactory);
	}

	/**
	 * You can use <code>Bosk::simpleDriver</code> as the
	 * <code>driverFactory</code> if you don't want any additional driver modules.
	 */
	public static <RR extends Entity> BoskDriver<RR> simpleDriver(BoskDriver<RR> downstream, @SuppressWarnings("unused") Bosk<RR> bosk) {
		return downstream;
	}

	/**
	 * {@link BoskDriver} that writes directly to this {@link Bosk}.
	 *
	 * <p>
	 * Acts as the gatekeeper for state changes. This object is what provides thread safety.
	 *
	 * <p>
	 * Provides three guarantees:
	 *
	 * <ol><li>
	 * All updates submitted to this driver are applied to the Bosk state in order.
	 * </li><li>
	 * Updates are acknowledged synchronously: errors that make an update inapplicable
	 * are detected at submission time (eg. containing object doesn't exist).
	 * </li><li>
	 * Hooks are run serially: no hook begins until the previous one finishes.
	 * </li></ol>
	 *
	 * Satisfying all of these simultaneously is tricky, especially because we can't just put
	 * "synchronized" on the submit methods because that could cause deadlock.
	 *
	 * @author pdoyle
	 */
	@RequiredArgsConstructor
	@Accessors(fluent = true)
	private final class LocalDriver implements BoskDriver<R> {
		final DefaultRootFunction<R> initialRootFunction;
		final Deque<Runnable> hookExecutionQueue = new ConcurrentLinkedDeque<>();
		final Semaphore hookExecutionPermit = new Semaphore(1);

		@Override
		public R initialRoot(Type rootType) throws InvalidTypeException {
			R initialRoot = initialRootFunction.apply(Bosk.this);
			rawClass(rootType).cast(initialRoot);
			return initialRoot;
		}

		@Override
		public <T> void submitReplacement(Reference<T> target, T newValue) {
			synchronized (this) {
				R priorRoot = currentRoot;
				if (!tryGraftReplacement(target, newValue)) {
					return;
				}
				queueHooks(target, priorRoot);
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitInitialization(Reference<T> target, T newValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = new ReadContext(currentRoot)) {
					preconditionsSatisfied = !target.exists();
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftReplacement(target, newValue)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitDeletion(Reference<T> target) {
			synchronized (this) {
				R priorRoot = currentRoot;
				if (!tryGraftDeletion(target)) {
					return;
				}
				queueHooks(target, priorRoot);
			}
			drainQueueIfAllowed();
		}

		@Override
		public void flush() {
			// Nothing to do here. Updates are applied to the current state immediately as they arrive.
			// No need to drain the hook queue because `flush` makes no guarantees about hooks.
		}

		@Override
		public <T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = new ReadContext(currentRoot)) {
					preconditionsSatisfied = Objects.equals(precondition.value(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftReplacement(target, newValue)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		@Override
		public <T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue) {
			synchronized (this) {
				boolean preconditionsSatisfied;
				try (@SuppressWarnings("unused") ReadContext executionContext = new ReadContext(currentRoot)) {
					preconditionsSatisfied = Objects.equals(precondition.value(), requiredValue);
				}
				if (preconditionsSatisfied) {
					R priorRoot = currentRoot;
					if (!tryGraftDeletion(target)) {
						return;
					}
					queueHooks(target, priorRoot);
				}
			}
			drainQueueIfAllowed();
		}

		/**
		 * Run the given hook on every existing object that matches its scope.
		 */
		void triggerEverywhere(HookRegistration<?> reg) {
			synchronized (this) {
				triggerQueueingOfHooks(rootReference(), null, currentRoot, reg);
			}
			drainQueueIfAllowed();
		}

		/**
		 * @return false if the update was ignored
		 */
		@SuppressWarnings("unchecked")
		private synchronized <T> boolean tryGraftReplacement(Reference<T> target, T newValue) {
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying replacement at {}", target);
				currentRoot = (R) requireNonNull(dereferencer.with(currentRoot, target, requireNonNull(newValue)));
			} catch (AccessorThrewException e) {
				throw new AccessorThrewException("Unable to submitReplacement(\"" + target + "\", " + newValue + ")", e);
			} catch (NonexistentEntryException e) {
				LOGGER.debug("Ignoring replacement of {}", target, e);
				return false;
			}
			return true;
		}

		/**
		 * @return false if the update was ignored
		 */
		@SuppressWarnings("unchecked")
		private synchronized <T> boolean tryGraftDeletion(Reference<T> target) {
			Path targetPath = target.path();
			if (targetPath.length() == 0) {
				throw new IllegalArgumentException("Cannot delete root object");
			}
			Dereferencer dereferencer = dereferencerFor(target);
			try {
				LOGGER.debug("Applying deletion at {}", target);
				currentRoot = (R)requireNonNull(dereferencer.without(currentRoot, target));
			} catch (AccessorThrewException e) {
				throw new AccessorThrewException("Unable to submitDeletion(\"" + target + "\")", e);
			} catch (NonexistentEntryException e) {
				LOGGER.debug("Ignoring deletion of {}", target, e);
				return false;
			}
			return true;
		}

		private Dereferencer dereferencerFor(Reference<?> ref) {
			// We could just pull it out of ref, if it's a ReferenceImpl, but we can't assume that
			return compileVettedPath(ref.path());
		}

		private <T> void queueHooks(Reference<T> target, @Nullable R priorRoot) {
			R rootForHook = currentRoot;
			for (HookRegistration<?> reg: hooks) {
				triggerQueueingOfHooks(target, priorRoot, rootForHook, reg);
			}
		}

		/**
		 * For a given {@link HookRegistration}, queues up a call to {@link BoskHook#onChanged}
		 * for each matching object that changed between <code>priorRoot</code> and <code>rootForHook</code>
		 * when <code>target</code> was updated. If <code>priorRoot</code> is null, the hook is called
		 * on every matching object that exists in <code>rootForHook</code>.
		 */
		private <T,S> void triggerQueueingOfHooks(Reference<T> target, @Nullable R priorRoot, R rootForHook, HookRegistration<S> reg) {
			reg.triggerAction(priorRoot, rootForHook, target, changedRef -> {
				LOGGER.debug("Hook: queue {} due to {}", changedRef, target);
				hookExecutionQueue.addLast(() -> {
					try (@SuppressWarnings("unused") ReadContext executionContext = new ReadContext(rootForHook)) {
						LOGGER.debug("Hook: RUN {}", changedRef);
						reg.hook.onChanged(changedRef);
					}
				});
			});
		}

		/**
		 * Runs queued hooks in a "breadth-first" fashion: all hooks "H" triggered by
		 * any single hook "G" will run before any consequent hooks triggered by "H".
		 *
		 * <p>
		 * The <a href="https://en.wikipedia.org/w/index.php?title=Breadth-first_search&oldid=1059916234#Pseudocode">classic BFS algorithm</a>
		 * has an outer loop that dequeues nodes for processing; however, we have an
		 * "inversion of control" situation here, where the bosk is not in control of
		 * the outermost loop.
		 *
		 * <p>
		 * Instead, we maintain a semaphore to distinguish "outermost calls" from
		 * "recursive calls", and dequeue nodes only at the outermost level, thereby
		 * effectively implementing the classic BFS algorithm despite not having access
		 * to the outermost loop of the application. The dequeuing is "allowed" only
		 * at the outermost level.
		 *
		 * <p>
		 * As a side-benefit, this also provides thread safety, as well as intuitive behaviour
		 * in the presence of parallelism.
		 *
		 * <p>
		 * Note: don't call while holding this object's monitor (ie. from a synchronized
		 * block). Running hooks means running arbitrary user code, which can take an
		 * arbitrary amount of time, and if the monitor is held, that blocks other
		 * threads from submitting updates.
		 */
		private void drainQueueIfAllowed() {
			do {
				if (hookExecutionPermit.tryAcquire()) {
					try {
						for (Runnable ex = hookExecutionQueue.pollFirst(); ex != null; ex = hookExecutionQueue.pollFirst()) {
							try {
								ex.run();
							} catch (Exception | AssertionError e) {
								LOGGER.error("Hook aborted due to exception: {}",  e.getMessage(), e);
							}
						}
					} finally {
						hookExecutionPermit.release();
					}
				} else {
					LOGGER.debug("Not draining the hook queue");
					return;
				}

				// The do-while loop here needs an explanation. At this location in the code,
				// we need to check again whether the queue is empty. Here's why.
				//
				// Events:
				//  - Q: Queue a hook
				//  - A: Acquire the permit
				//  - D: Drain the queue till it's empty
				//  - R: Release the permit
				//  - F: Try to acquire the permit and fail
				//
				// The two threads:
				//   This thread        Other thread
				//        Q
				//        A
				//        D
				//                         Q
				//                         F
				//        R
				//        * <-- (You are here)
				//
				// At this point, the queue may not be empty, yet this thread thinks it's drained,
				// and the other thread thinks we'll drain it.
				//
				// Fortunately, the solution is simple: just check again. If the queue is empty
				// at this point, we can safely stop running hooks, secure in the knowledge that
				// if another thread queues another hook after this point, that thread will also
				// succeed in acquiring the permit and will itself drain the queue.

			} while (!hookExecutionQueue.isEmpty());
		}

	}

	/**
	 * Causes the given {@link BoskHook} to be called when the given scope
	 * object is updated. Hooks are called in the order they were registered.
	 *
	 * <p>
	 * Before returning, runs the hook on the current state.
	 */
	public <T> void registerHook(@NonNull Reference<T> scope, @NonNull BoskHook<T> hook) {
		HookRegistration<T> reg = new HookRegistration<>(requireNonNull(scope), requireNonNull(hook));
		hooks.add(reg);
		localDriver.triggerEverywhere(reg);
	}

	@RequiredArgsConstructor
	@ToString
	private final class HookRegistration<S> {
		final Reference<S> scope;
		final BoskHook<S> hook;

		/**
		 * Calls <code>action</code> for every object whose path matches <code>scope</code> that
		 * was changed by a driver event targeting <code>target</code>.
		 *
		 * @param priorRoot The bosk root object before the driver event occurred
		 * @param newRoot The bosk root object after the driver event occurred
		 * @param target The object specified by the driver event
		 * @param action The operation to perform for each matching object that could have changed
		 */
		private void triggerAction(@Nullable R priorRoot, R newRoot, Reference<?> target, Consumer<Reference<S>> action) {
			Reference<S> effectiveScope;
			int relativeDepth = target.path().length() - scope.path().length();
			if (relativeDepth >= 0) {
				Path candidate = target.path().truncatedBy(relativeDepth);
				if (scope.path().matches(candidate)) {
					effectiveScope = scope.boundBy(candidate);
				} else {
					return;
				}
			} else {
				Path enclosingScope = scope.path().truncatedBy(-relativeDepth);
				if (enclosingScope.matches(target.path())) {
					effectiveScope = scope.boundBy(target.path());
				} else {
					return;
				}
			}
			triggerCascade(effectiveScope, priorRoot, newRoot, action);
		}
	}

	/**
	 * Recursive helper routine that calls the given action for all objects matching <code>effectiveScope</code> that
	 * are different between <code>priorRoot</code> and <code>newRoot</code>.
	 *
	 * @param effectiveScope The hook scope with zero or more of its parameters filled in
	 * @param priorRoot The root before the change that triggered the hook; or null during initialization when running
	 *                  hooks on the {@link BoskDriver#initialRoot initial root}.
	 * @param newRoot The root after the change that triggered the hook. This will be the root in the {@link ReadContext}
	 *                during hook execution.
	 * @param action The operation to perform for each matching object that is different between the two roots
	 * @param <S> The type of the hook scope object
	 */
	private <S> void triggerCascade(Reference<S> effectiveScope, @Nullable R priorRoot, R newRoot, Consumer<Reference<S>> action) {
		if (effectiveScope.path().numParameters() == 0) {
			S priorValue = refValueIfExists(effectiveScope, priorRoot);
			S currentValue = refValueIfExists(effectiveScope, newRoot);
			if (priorValue == currentValue) { // Note object identity comparison
				LOGGER.debug("Hook: skip unchanged {}", effectiveScope);
			} else {
				// We've found something that changed
				action.accept(effectiveScope);
			}
		} else {
			try {
				// There's at least one parameter that hasn't been bound yet. This means
				// we need to locate all the matching objects that may have changed.
				// We do so by filling in the first parameter with all possible values that
				// could correspond to changed objects and then recursing.
				//
				Path containerPath = effectiveScope.path().truncatedTo(effectiveScope.path().firstParameterIndex());
				Reference<EnumerableByIdentifier<?>> containerRef = reference(enumerableByIdentifierClass(), containerPath);
				EnumerableByIdentifier<?> priorContainer = refValueIfExists(containerRef, priorRoot);
				EnumerableByIdentifier<?> newContainer = refValueIfExists(containerRef, newRoot);

				// Process deleted items first. This might allow the hook to free some resources
				// that could be used by subsequent hooks.
				// We do them in reverse order just because that's likely to be the preferred
				// order for cleanup activities.
				//
				// TODO: Should we actually process the hooks themselves in reverse order for the same reason?
				//
				if (priorContainer != null) {
					List<Identifier> priorIDs = priorContainer.ids();
					for (ListIterator<Identifier> iter = priorIDs.listIterator(priorIDs.size()); iter.hasPrevious(); ) {
						Identifier id = iter.previous();
						if (newContainer == null || newContainer.get(id) == null) {
							triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
						}
					}
				}

				// Then process updated items
				//
				if (newContainer != null) {
					for (Identifier id: newContainer.ids()) {
						if (priorContainer == null || priorContainer.get(id) != newContainer.get(id)) {
							triggerCascade(effectiveScope.boundTo(id), priorRoot, newRoot, action);
						}
					}
				}
			} catch (InvalidTypeException e) {
				// TODO: Add truncation methods to Reference so we can refactor this to create
				// the container reference without risking an InvalidTypeException
				throw new NotYetImplementedException(e);
			}
		}
	}

	@Nullable
	private <V> V refValueIfExists(Reference<V> containerRef, @Nullable R priorRoot) {
		if (priorRoot == null) {
			return null;
		} else {
			// TODO: This would be less cumbersome if we could apply a Reference to an arbitrary root object.
			// For now, References only apply to the current ReadContext, so we need a new ReadContext every time
			// we want to change roots.
			try (@SuppressWarnings("unused") ReadContext priorContext = new ReadContext(priorRoot)) {
				return containerRef.valueIfExists();
			}
		}
	}

	/**
	 * A thread-local region in which {@link Reference#value()} works; outside
	 * of a {@link ReadContext}, {@link Reference#value()} will throw {@link
	 * IllegalStateException}.
	 *
	 * @author pdoyle
	 */
	public final class ReadContext implements AutoCloseable {
		final R originalRoot;
		final R snapshot;

		/**
		 * Creates a {@link ReadContext} for the current thread. If one is already
		 * active on this thread, the new nested one will be equivalent and has
		 * no effect.
		 */
		private ReadContext() {
			originalRoot = rootSnapshot.get();
			if (originalRoot == null) {
				snapshot = currentRoot;
				rootSnapshot.set(snapshot);
				LOGGER.trace("New " + this);
			} else {
				// Inner scopes use the same snapshot as outer scopes
				snapshot = originalRoot;
				LOGGER.trace("Nested " + this);
			}
		}

		/**
		 * Creates a {@link ReadContext} for the current thread, inheriting state
		 * from another thread.
		 *
		 * <p>
		 * Because nested scopes behave like their outer scope, you can always
		 * make another ReadContext at any time on some thread in order to
		 * "capture" whatever scope may be in effect on that thread (or to
		 * create a new one if there is no active scope on that thread).
		 *
		 * <p>
		 * Hence, a recommended idiom for scope inheritance looks like this:
		 *
		 * <blockquote><pre>
try (ReadContext originalThReadContext = bosk.new ReadContext()) {
	workQueue.submit(() -> {
		try (ReadContext workerThReadContext = bosk.new ReadContext(originalThReadContext)) {
			// Code in here can read from the bosk just like the original thread.
		}
	});
}
		 * </pre></blockquote>
		 *
		 * Note, though, that this will prevent the garbage collector from
		 * collecting the ReadContext's state snapshot until the worker thread's
		 * scope is finished.  Hence, you want to use this technique if you want
		 * to ensure that the worker thread sees the same bosk state snapshot as
		 * the original thread. This is usually a good idea. However, if the
		 * worker thread is to run after the original thread would have exited
		 * its own scope, then use this idiom only if the worker thread must see
		 * the same state snapshot as the original thread <em>and</em> you're
		 * willing to prevent that snapshot from being garbage-collected until
		 * the worker thread finishes.
		 *
		 * @param toInherit a {@link ReadContext} created by another original
		 * thread, causing {@link Reference#value()} on this thread to behave as
		 * though it were called on the original thread.
		 */
		private ReadContext(ReadContext toInherit) {
			R snapshotToInherit = requireNonNull(toInherit.snapshot);
			originalRoot = rootSnapshot.get();
			if (originalRoot == null) {
				rootSnapshot.set(this.snapshot = snapshotToInherit);
				LOGGER.trace("Sharing " + this);
			} else if (originalRoot == snapshotToInherit) {
				// Some thread pools recruit the calling thread itself; don't want to disallow this.
				this.snapshot = originalRoot;
				LOGGER.trace("Re-sharing " + this);
			} else {
				throw new IllegalStateException("Read scope for " + name + " already active in " + Thread.currentThread());
			}
		}

		/**
		 * Internal constructor to use a given root.
		 *
		 * <p>
		 * Unlike the other constructors, this can be used to substitute a new root temporarily,
		 * even if there's already one active on the current thread.
		 */
		ReadContext(@Nonnull R root) {
			originalRoot = rootSnapshot.get();
			snapshot = requireNonNull(root);
			rootSnapshot.set(snapshot);
			LOGGER.trace("Using " + this);
		}

		/**
		 * Establish a new context on the current thread using the same state
		 * as <code>this</code> context.
		 * @return a <code>ReadContext</code> representing the new context.
		 */
		public ReadContext adopt() {
			return new ReadContext(this);
		}

		@Override
		public void close() {
			LOGGER.trace("Exiting " + this + "; restoring " + System.identityHashCode(originalRoot));
			rootSnapshot.set(originalRoot);
		}

		@Override
		public String toString() {
			return "ReadContext(" + System.identityHashCode(snapshot) + ")";
		}
	}

	public ReadContext readContext() {
		return new ReadContext();
	}

	/**
	 * A path is "vetted" if we've already called {@link #pathCompiler}.{@link PathCompiler#targetTypeOf} on it.
	 */
	private Dereferencer compileVettedPath(Path path) {
		try {
			return pathCompiler.compiled(path);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Compiling a vetted path should not throw InvalidTypeException: " + path, e);
		}
	}

	@Accessors(fluent=true)
	@RequiredArgsConstructor
	private abstract class ReferenceImpl<T> implements Reference<T> {
		@Getter protected final Path path;
		@Getter protected final Type targetType;

		@Override
		@SuppressWarnings("unchecked")
		public final Class<T> targetClass() {
			return (Class<T>)rawClass(targetType());
		}

		@Override
		public final Reference<T> boundBy(BindingEnvironment bindings) {
			return newReference(path.boundBy(bindings), targetType);
		}

		@Override
		public final <U> Reference<U> then(Class<U> targetClass, String... segments) throws InvalidTypeException {
			return reference(targetClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> CatalogReference<U> thenCatalog(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return catalogReference(entryClass, path.then(segments));
		}

		@Override
		public final <U extends Entity> ListingReference<U> thenListing(Class<U> entryClass, String... segments) throws InvalidTypeException {
			return listingReference(entryClass, path.then(segments));
		}

		@Override
		public final <K extends Entity, V> SideTableReference<K, V> thenSideTable(Class<K> keyClass, Class<V> valueClass, String... segments) throws InvalidTypeException {
			return sideTableReference(keyClass, valueClass, path.then(segments));
		}

		@Override
		public final <TT> Reference<Reference<TT>> thenReference(Class<TT> targetClass, String... segments) throws InvalidTypeException {
			return referenceReference(targetClass, path.then(segments));
		}

		@SuppressWarnings("unchecked")
		@Override
		public final <TT> Reference<TT> enclosingReference(Class<TT> targetClass) throws InvalidTypeException {
			if (path.isEmpty()) {
				throw new InvalidTypeException("Root reference has no enclosing references");
			}
			for (Path p = this.path.truncatedBy(1); !p.isEmpty(); p = p.truncatedBy(1)) try {
				Type targetType = pathCompiler.targetTypeOf(p);
				if (targetClass.isAssignableFrom(rawClass(targetType))) {
					return reference(targetClass, p);
				}
			} catch (InvalidTypeException e) {
				throw new InvalidTypeException("Error looking up enclosing " + targetClass.getSimpleName() + " from " + path);
			}
			// Might be the root
			if (targetClass.isAssignableFrom(rawClass(rootType))) {
				return (Reference<TT>) rootReference();
			} else {
				throw new InvalidTypeException("No enclosing " + targetClass.getSimpleName() + " from " + path);
			}
		}

		@Override
		public final int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + rootType().hashCode();
			result = prime * result + path.hashCode();
			return result;
		}

		@Override
		public final boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (!(obj instanceof ReferenceImpl)) {
				return false;
			}

			// Two references are equal if they have the same root type and path.
			// Note that they are not required to come from the same Bosk.
			// That means we can compare references from one Bosk to the other
			// if they both have the same root type.

			@SuppressWarnings({"rawtypes", "unchecked"})
			ReferenceImpl other = (ReferenceImpl) obj;
			if (this.rootType() == other.rootType()) {
				return Objects.equals(path, other.path);
			} else {
				return false;
			}
		}

		private Type rootType() {
			return Bosk.this.rootType;
		}

		@Override
		public final String toString() {
			return path.toString();
		}

	}

	/**
	 * A {@link Reference} with no unbound parameters.
	 */
	private final class DefiniteReference<T> extends ReferenceImpl<T> {
		@Getter(lazy = true) private final Dereferencer dereferencer = compileVettedPath(path);

		public DefiniteReference(Path path, Type targetType) {
			super(path, targetType);
			assert path.numParameters() == 0;
		}

		@Override
		@SuppressWarnings("unchecked")
		public T valueIfExists() {
			R snapshot = rootSnapshot.get();
			LOGGER.trace("Snapshot is {}", System.identityHashCode(snapshot));
			if (snapshot == null) {
				throw new NoReadContextException("No active read context for " + name + " in " + Thread.currentThread());
			} else try {
				return (T) dereferencer().get(snapshot, this);
			} catch (NonexistentEntryException e) {
				return null;
			}
		}

		@Override
		public void forEachValue(BiConsumer<T, BindingEnvironment> action, BindingEnvironment existingEnvironment) {
			T value = valueIfExists();
			if (value != null) {
				action.accept(value, existingEnvironment);
			}
		}
	}

	/**
	 * A {@link Reference} with at least one unbound parameter.
	 * All parameters must be bound before the Reference can be used for {@link #value()} etc.
	 *
	 * <p>
	 * It is an error to have a parameter in a position that does not
	 * correspond to an {@link Identifier} that can be looked up in an
	 * object that implements {@link EnumerableByIdentifier}. (We are
	 * not offering to use reflection to look up object fields by name here.)
	 *
	 * TODO: This is not currently checked or enforced; it will just cause confusing crashes.
	 * It should throw {@link InvalidTypeException} at the time the Reference is created.
	 */
	private final class IndefiniteReference<T> extends ReferenceImpl<T> {
		public IndefiniteReference(Path path, Type targetType) {
			super(path, targetType);
			assert path.numParameters() >= 1;
		}

		@Override
		public T valueIfExists() {
			throw new ReferenceBindingException("Reference has unbound parameters: " + this);
		}

		@Override
		public void forEachValue(BiConsumer<T, BindingEnvironment> action, BindingEnvironment existingEnvironment) {
			int firstParameterIndex = path.firstParameterIndex();
			String parameterName = parameterNameFromSegment(path.segment(firstParameterIndex));
			Path containerPath = path.truncatedTo(firstParameterIndex);
			Reference<EnumerableByIdentifier<?>> containerRef;
			try {
				containerRef = reference(enumerableByIdentifierClass(), containerPath);
			} catch (InvalidTypeException e) {
				throw new NotYetImplementedException("Parameter reference should come after a " + EnumerableByIdentifier.class, e);
			}
			EnumerableByIdentifier<?> container = containerRef.valueIfExists();
			if (container != null) {
				container.ids().forEach(id ->
					this.boundTo(id).forEachValue(action,
						existingEnvironment.builder()
							.bind(parameterName, id)
							.build()
					));
			}
		}

	}

	private <T> Reference<T> newReference(Path path, Type targetType) {
		if (path.numParameters() == 0) {
			return new DefiniteReference<>(path, targetType);
		} else {
			return new IndefiniteReference<>(path, targetType);
		}
	}

	/**
	 * An {@link Optional#empty()}, or missing {@link Catalog} or
	 * {@link SideTable} entry, was encountered by a Locator when walking along
	 * object fields, indicating that the desired item is absent.
	 *
	 * <p>
	 * This is an internal exception used in the implementation of Bosk.
	 * It differs from {@link org.vena.bosk.exceptions.NonexistentReferenceException},
	 * which is a user-facing exception that is part of the contract of {@link Reference#value()}.
	 */
	@Getter
	@Accessors(fluent=true)
	public static final class NonexistentEntryException extends Exception {
		final Path path;

		public NonexistentEntryException(Path path) {
			super("No object at path \"" + path.toString() + "\"");
			this.path = path;
		}
	}

	//
	// Reference factory methods
	//

	public final <T> Reference<T> reference(Class<T> requestedClass, Path path) throws InvalidTypeException {
		Type targetType;
		try {
			targetType = pathCompiler.targetTypeOf(path);
		} catch (InvalidTypeException e) {
			throw new InvalidTypeException("Invalid path: " + path, e);
		}
		Class<?> targetClass = rawClass(targetType);
		if (Optional.class.isAssignableFrom(requestedClass)) {
			throw new InvalidTypeException("Reference<Optional<T>> not supported; create a Reference<T> instead and use Reference.optionalValue()");
		} else if (!requestedClass.isAssignableFrom(targetClass)) {
			throw new InvalidTypeException("Path from " + rawClass(rootType).getSimpleName()
				+ " returns " + targetClass.getSimpleName()
				+ "; requested " + requestedClass.getSimpleName()
				+ ": " + path);
		} else if (Reference.class.isAssignableFrom(requestedClass)) {
			// TODO: Disallow references to implicit references {Self and Enclosing}
		}
		return newReference(path, targetType);
	}

	@SuppressWarnings("unchecked")
	public final Reference<R> rootReference() {
		try {
			return (Reference<R>)reference(rawClass(rootType), Path.empty());
		} catch (InvalidTypeException e) {
			throw new AssertionError("Root reference must be of class " + rawClass(rootType).getSimpleName(), e);
		}
	}

	public final <T extends Entity> CatalogReference<T> catalogReference(Class<T> entryClass, Path path) throws InvalidTypeException {
		Reference<Catalog<T>> ref = reference(Classes.catalog(entryClass), path);
		return new CatalogRef<>(ref, entryClass);
	}

	public final <T extends Entity> ListingReference<T> listingReference(Class<T> entryClass, Path path) throws InvalidTypeException {
		Reference<Listing<T>> ref = reference(Classes.listing(entryClass), path);
		return new ListingRef<>(ref);
	}

	public final <K extends Entity,V> SideTableReference<K,V> sideTableReference(Class<K> keyClass, Class<V> valueClass, Path path) throws InvalidTypeException {
		Reference<SideTable<K,V>> ref = reference(Classes.sideTable(keyClass, valueClass), path);
		return new SideTableRef<>(ref, keyClass, valueClass);
	}

	public final <TT> Reference<Reference<TT>> referenceReference(Class<TT> targetClass, Path path) throws InvalidTypeException {
		return reference(Classes.reference(targetClass), path);
	}

	@Override
	public final String toString() {
		return instanceID() + " \"" + name + "\"::" + rawClass(rootType).getSimpleName();
	}

	/**
	 * FOR UNIT TESTING
	 */
	final R currentRoot() {
		return currentRoot;
	}

	@SuppressWarnings({"unchecked","rawtypes"})
	private static Class<EnumerableByIdentifier<?>> enumerableByIdentifierClass() {
		return (Class) EnumerableByIdentifier.class;
	}
	private static final Logger LOGGER = LoggerFactory.getLogger(Bosk.class);
}
