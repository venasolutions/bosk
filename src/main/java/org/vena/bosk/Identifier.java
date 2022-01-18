package org.vena.bosk;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
public final class Identifier {
	@NonNull final String value;

	// TODO: Intern these.  No need to have several Identifier objects for the same value
	public static Identifier from(String value) {
		return new Identifier(value);
	}

	/**
	 * I'm going to regret adding this.
	 */
	public static synchronized Identifier unique(String prefix) {
		return new Identifier(prefix + (++uniqueIdCounter));
	}

	private static long uniqueIdCounter = 1000;

	@Override public String toString() { return value; }
}
