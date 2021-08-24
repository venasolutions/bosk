package org.vena.bosk;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Identifier {
	@NonNull final String value;

	// TODO: Intern these.  No need to have several Identifier objects for the same value
	public static Identifier from(String value) { return new Identifier(value); }

	/**
	 * I'm going to regret adding this.
	 */
	public static synchronized Identifier unique(String prefix) {
		return new Identifier(prefix + (++uniqueIdCounter));
	}

	private static long uniqueIdCounter = 1000;

	@Override public int hashCode() { return value.hashCode(); }

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		Identifier other = (Identifier) obj;
		return value.equals(other.value);
	}

	@Override public String toString() { return value; }
}
