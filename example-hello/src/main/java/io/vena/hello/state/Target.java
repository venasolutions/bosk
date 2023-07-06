package io.vena.hello.state;

import io.vena.bosk.Entity;
import io.vena.bosk.Identifier;

/**
 * Someone to be greeted.
 */
public record Target(
	Identifier id
) implements Entity { }
