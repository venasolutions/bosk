package io.vena.bosk;

import io.vena.bosk.Bosk.ReadContext;
import io.vena.bosk.drivers.ForwardingDriver;
import io.vena.bosk.exceptions.FlushFailureException;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Receives update requests for some {@link Bosk}.
 *
 * @author pdoyle
 */
public interface BoskDriver<R extends Entity> {
	/**
	 * Returns the root object the {@link Bosk} should use as its initial state upon
	 * returning from its constructor.
	 *
	 * <p>
	 * Meant to be called only once during initialization by the Bosk;
	 * the behaviour of subsequent calls depends on the implementation,
	 * and may even throw an exception. As a convenience to implementations,
	 * this method is allowed to throw a variety of checked exceptions
	 * that are common to implementations.
	 *
	 * <p>
	 * For a "stackable layer" driver, it is conventional to delegate to the
	 * downstream implementation of this method whenever the layer itself has
	 * no initial state to supply. For example, a driver backed by a database
	 * could delegate to its downstream driver in the case that the database
	 * is empty, and could use the resulting initial state to initialize th
	 * database.
	 *
	 * @param rootType The full {@link Type} of the root object, including any
	 * type parameters if it's parameterized, as a convenience to the initialization logic.
	 * @throws InvalidTypeException as a convenience to support initialization logic
	 * that creates {@link Reference References} (which is very common) so that implementations
	 * do not need to catch that exception and wrap it or otherwise deal with it:
	 * the caller of this method is expected to know how to deal with that exception.
	 * @throws UnsupportedOperationException if this driver is unable to provide
	 * an initial root. Such a driver cannot be used on its own to initialize a Bosk,
	 * but it can be used downstream of a {@link ForwardingDriver} provided there is
	 * another downstream driver that can provide the initial root instead.
	 *
	 * @see io.vena.bosk.exceptions.InitializationFailureException
	 */
	R initialRoot(Type rootType) throws InvalidTypeException, IOException, InterruptedException;

	/**
	 * Requests that the object referenced by <code>target</code> be changed to <code>newValue</code>.
	 *
	 * <p>
	 * Changes will not be visible in the {@link io.vena.bosk.Bosk.ReadContext} in which this method
	 * was called. If <code>target</code> is inside an enclosing object that does not exist at the
	 * time the update is applied, it is silently ignored.
	 */
	<T> void submitReplacement(Reference<T> target, Optional<T> newValue);

	/**
	 * Like {@link #submitReplacement}, but has no effect unless
	 * <code>precondition.valueIfExists()</code> is equal to <code>requiredValue</code>
	 * immediately before the deletion.  <code>requiredValue</code> must not be null.
	 *
	 * @see #submitReplacement
	 */
	<T> void submitConditionalReplacement(Reference<T> target, T newValue, Reference<Identifier> precondition, Identifier requiredValue);

	/**
	 * Like {@link #submitReplacement}, but has no effect if the target object already exists.
	 *
	 * @see #submitReplacement
	 */
	<T> void submitInitialization(Reference<T> target, T newValue);

	/**
	 * Requests that the object referenced by <code>target</code> be deleted.
	 * The object must be deletable; it must be an entry in a {@link Catalog}, {@link Listing},
	 * or {@link SideTable}; or else it must be an {@link java.util.Optional} in which case
	 * it will be changed to {@link java.util.Optional#empty()}.
	 *
	 * <p>
	 * Changes will not be visible in the {@link ReadContext} in which this method
	 * was called. If <code>target.exists()</code> is false at the time this update
	 * is to be applied, it is silently ignored.
	 *
	 * @throws IllegalArgumentException if the targeted object is not deletable,
	 * regardless of whether it exists.
	 */
	<T> void submitDeletion(Reference<T> target);

	/**
	 * Like {@link #submitDeletion(Reference)} but has no effect unless
	 * <code>precondition.valueIfExists()</code> is equal to <code>requiredValue</code>
	 * immediately before the deletion.  <code>requiredValue</code> must not be null.
	 *
	 * @see #submitDeletion(Reference)
	 */
	<T> void submitConditionalDeletion(Reference<T> target, Reference<Identifier> precondition, Identifier requiredValue);

	/**
	 * Blocks until all prior updates have been applied to the Bosk.
	 *
	 * <p>
	 * <em>
	 * Note: Use of this method in application code is a smell.
	 * If you feel the need to call this in your application code, there's a pretty good chance
	 * you have logic that should be in a hook and isn't.
	 * It's intended to be called in system-level code and test cases, in order to provide
	 * the desired ordering guarantees.
	 * </em>
	 *
	 * <p>
	 * The definition of "prior" is intuitively the same as the "happens-before" relationship in
	 * the Java memory model, and includes:
	 *
	 * <ul><li>
	 *    any operation that "happens before" this call according to the Java memory model.
	 *    In particular,
	 * </li><li>
	 *    any operation that already happened on the same thread that called this method.
	 * </li><li>
	 *    any operation on any server that was successfully submitted to any bosk driver
	 *    configured to use the same backing database as this one.
	 * </li></ul>
	 *
	 * All of these events "happen before" this method returns.
	 * If a {@link ReadContext} is acquired after this method returns,
	 * all of the effects of the above operations (and possibly some additional subsequent operations)
	 * will be reflected in the bosk state.
	 * Hooks triggered by the above operations may or may not have run before this method returns.
	 *
	 * <p>
	 * If you are familiar with the Java memory model, then all BoskDriver operations are
	 * "synchronizing operations" meaning that there exists a global ordering between all
	 * driver operations, even in different servers, as long as they are "the same bosk"
	 * (eg. backed by the same database).
	 * This method provides a kind of "no-op" synchronizing operation that allows you to reason
	 * about the order of events such as reads that would not otherwise have a well-defined order.
	 *
	 * <p>
	 * This is expected to be an expensive operation, so callers should avoid calling this
	 * unless its strong semantics are required.
	 * For "stackable layer" drivers, this usually means they should not call this except
	 * to implement their own <code>flush</code> method.
	 *
	 * <p>
	 * <strong>Evolution note</strong>: This method currently acts as a full barrier, while
	 * ultimately we may want a more efficient release-acquire pair that allows writes
	 * to be reliably visible to subsequent reads.
	 *
	 * @see FlushFailureException
	 */
	void flush() throws IOException, InterruptedException;

	// Handy helpers

	/**
	 * Equivalent to:
	 *
	 * <p>
	 * <code>
	 * submitReplacement(newValue.reference(), newValue);
	 * </code>
	 */
	// TODO: convert to Optional
	default <T extends ReflectiveEntity<T>> void submitReplacement(T newValue) {
		submitReplacement(newValue.reference(), Optional.of(newValue));
	}

}
