package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;

/**
 * Provides access to a subset of bosk functionality that is available at the time
 * the {@link BoskDriver} is built, before the {@link Bosk} itself is fully initialized.
 */
public interface BoskInfo<R extends StateTreeNode> {
	String name();
	Identifier instanceID();
	RootReference<R> rootReference();
	void registerHooks(Object receiver) throws InvalidTypeException;
}
