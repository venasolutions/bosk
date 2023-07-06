package io.vena.hello.state;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;

/**
 * The root of the {@link Bosk} state tree.
 */
public record BoskState(
	Identifier id,
	Catalog<Target> targets
) implements Entity { }
