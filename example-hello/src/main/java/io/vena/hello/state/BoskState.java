package io.vena.hello.state;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.StateTreeNode;

/**
 * The root of the {@link Bosk} state tree.
 */
public record BoskState(
	Catalog<Target> targets
) implements StateTreeNode { }
