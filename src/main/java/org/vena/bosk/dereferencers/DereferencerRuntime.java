package org.vena.bosk.dereferencers;

import java.util.Optional;
import org.vena.bosk.Bosk.NonexistentEntryException;
import org.vena.bosk.Catalog;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.Mapping;
import org.vena.bosk.Reference;

import static org.vena.bosk.ListingEntry.LISTING_ENTRY;

/**
 * Helper methods called by compiled {@link Dereferencer}s.
 *
 * <p>
 * Because compiled code is loaded by a different {@link ClassLoader}, it will
 * appear to be in a different "runtime package" and therefore can't access
 * package-private classes; hence, we must make this public.
 */
public abstract class DereferencerRuntime implements Dereferencer {
	protected static Object invalidWithout(Object notCollection, Reference<?> ref) {
		throw new IllegalArgumentException("Cannot remove " + ref.path() + " from " + notCollection.getClass().getSimpleName());
	}

	protected static Object throwNonexistentEntry(Reference<?> ref) throws NonexistentEntryException {
		throw new NonexistentEntryException(ref.path());
	}

	protected static Object throwCannotReplacePhantom(Reference<?> ref) {
		throw new IllegalArgumentException("Cannot replace phantom " + ref);
	}

	protected static Object optionalOrThrow(Optional<?> optional, Reference<?> ref) throws NonexistentEntryException {
		return optional.orElseThrow(() -> new NonexistentEntryException(ref.path()));
	}

	protected static Object catalogEntryOrThrow(Catalog<?> catalog, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		Object result = catalog.get(id);
		if (result == null) {
			throw new NonexistentEntryException(ref.path());
		} else {
			return result;
		}
	}

	protected static Object listingEntryOrThrow(Listing<?> listing, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		if (listing.containsID(id)) {
			return LISTING_ENTRY;
		} else {
			throw new NonexistentEntryException(ref.path());
		}
	}

	protected static Object listingWith(Listing<?> listing, Identifier id, Object ignored) {
		return listing.withID(id);
	}

	protected static Object mappingEntryOrThrow(Mapping<?,?> mapping, Identifier id, Reference<?> ref) throws NonexistentEntryException {
		Object result = mapping.get(id);
		if (result == null) {
			throw new NonexistentEntryException(ref.path());
		} else {
			return result;
		}
	}

}
