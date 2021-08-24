package org.vena.bosk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.vena.bosk.Bosk.ReadContext;
import org.vena.bosk.exceptions.NonexistentReferenceException;

import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

/**
 * An ordered collection of references to entities housed in a {@link Catalog}, which
 * is accessible from {@link #catalog()}.
 *
 * @author pdoyle
 *
 * @param <E>
 */
@Accessors(fluent=true)
@EqualsAndHashCode
@RequiredArgsConstructor(access=AccessLevel.PACKAGE)
public final class Listing<E extends Entity> {
	@Getter
	private final CatalogReference<E> catalog;
	private final Set<Identifier> ids;

	public int size() { return ids.size(); }
	public boolean isEmpty() { return ids.isEmpty(); }

	@Override
	public String toString() {
		return catalog + "/" + ids;
	}

	//
	// ID-based methods.  Simple and efficient, though not as type-safe as the entity-based variants.
	//

	public List<Identifier> ids() {
		return unmodifiableList(new ArrayList<>(ids));
	}

	public boolean containsID(Identifier id) {
		return ids.contains(id);
	}

	public Stream<Identifier> idStream() {
		return ids.stream();
	}

	public Listing<E> withID(Identifier id) {
		if (ids.contains(id)) {
			return this;
		} else {
			Set<Identifier> newIDs = new LinkedHashSet<>(ids);
			newIDs.add(id);
			return new Listing<>(catalog, unmodifiableSet(newIDs));
		}
	}

	public Listing<E> withoutID(Identifier id) {
		if (ids.contains(id)) {
			Set<Identifier> newValues = new LinkedHashSet<>(ids);
			newValues.remove(id);
			return new Listing<>(catalog, unmodifiableSet(newValues));
		} else {
			return this;
		}
	}

	/**
	 * Much more efficient than repeated calls to {@link #withID}.
	 */
	public Listing<E> withAllIDs(Stream<Identifier> idsToAdd) {
		Set<Identifier> newIDs = new LinkedHashSet<>(this.ids);
		idsToAdd.forEachOrdered(newIDs::add);
		// TODO: If nothing was added, return this
		return new Listing<>(catalog, unmodifiableSet(newIDs));
	}

	//
	// "Entity" methods don't require a {@link ReadContext} and are more type-safe than
	// the corresponding ID-based methods, because you can't accidentally pass an ID
	// from the wrong object. The entity itself is used only for its ID.
	//

	public boolean containsEntity(E entity) {
		return containsID(entity.id());
	}

	public Listing<E> withEntity(E entity) {
		return this.withID(entity.id());
	}

	public Listing<E> withoutEntity(E entity) {
		return withoutID(entity.id());
	}

	//
	// "Value" methods return entities from {@link #catalog}. They require a read context
	// to call, but not afterward; for example, {@link #valueIterator} needs a read
	// context while constructing the Iterator object, but they don't need one while
	// consuming items from the Iterator.
	//

	/**
	 * @return <code>{@link #catalog}().{@link CatalogReference#then(Identifier)
	 * then}(id).{@link Reference#value() value}()</code> if <code>this.{@link
	 * #containsID(Identifier) containsID}(id)</code>, or <code>null</code>
	 * otherwise.
	 * @throws NonexistentReferenceException if {@link #catalog()} is nonexistent
	 * or does not contain an entity of the given <code>id</code>
	 */
	public E getValue(Identifier id) {
		if (ids.contains(id)) {
			return getOrThrow(catalog.value(), id);
		} else {
			return null;
		}
	}

	public Iterator<E> valueIterator() {
		return valueIteratorImpl(this.catalog.value());
	}

	public Spliterator<E> valueSpliterator() {
		return new CatalogLookupSpliterator(ids.spliterator(), catalog.value());
	}

	public Iterable<E> values() {
		// This whole method could just return this::valueIterator, but we can also
		// provide a better spliterator than the default one from Iterable because
		// we know a Listing qualifies for Spliterator.IMMUTABLE. And let's give
		// a nice toString too, for debugging.
		Catalog<E> catalog = this.catalog.value();
		return new Iterable<E>() {
			@Override
			public Iterator<E> iterator() {
				return valueIteratorImpl(catalog);
			}

			@Override
			public Spliterator<E> spliterator() {
				return new CatalogLookupSpliterator(ids.spliterator(), catalog);
			}

			@Override
			public String toString() {
				return stream(spliterator(), false).collect(toList()).toString();
			}
		};
	}

	public Stream<E> valueStream() {
		return stream(valueSpliterator(), false);
	}

	public List<E> valueList() {
		List<E> result = new ArrayList<>(size());
		valueIterator().forEachRemaining(result::add);
		return unmodifiableList(result);
	}

	public Map<Identifier, E> valueMap() {
		Map<Identifier, E> result = new LinkedHashMap<>();
		for (Identifier id: ids) {
			result.put(id, getOrThrow(catalog.value(), id));
		}
		return unmodifiableMap(result);
	}

	//
	// Static factory methods
	//

	public static <TT extends Entity> Listing<TT> empty(Reference<Catalog<TT>> catalog) {
		return new Listing<>(CatalogReference.from(catalog), emptySet());
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> catalog, Identifier...ids) {
		return Listing.of(catalog, Arrays.asList(ids));
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> catalog, Collection<Identifier> ids) {
		return new Listing<>(CatalogReference.from(catalog), new LinkedHashSet<>(ids));
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> catalog, Stream<Identifier> ids) {
		return Listing.of(catalog, ids.collect(toList()));
	}

	//
	// Set algebra
	//

	/**
	 * Note that <code>a.filteredBy(b)</code> has the same contents as
	 * <code>b.filteredBy(a)</code>, but in a potentially different order.
	 *
	 * @return {@link Listing} containing only those elements in both
	 * <code>this</code> and <code>other</code>, in the order the appear in
	 * <code>this</code>.
	 */
	public Listing<E> filteredBy(Listing<E> other) {
		Set<Identifier> newIDs = new LinkedHashSet<>(ids);
		newIDs.retainAll(other.ids);
		return new Listing<>(catalog, unmodifiableSet(newIDs));
	}

	//
	// Private helpers
	//

	private Iterator<E> valueIteratorImpl(Catalog<E> catalog) {
		Iterator<Identifier> iter = ids.iterator();
		return new Iterator<E>() {
			@Override public boolean hasNext() { return iter.hasNext(); }
			@Override public E next() { return getOrThrow(catalog, iter.next()); }
		};
	}

	/**
	 * Makes a Spliterator<E> out of a Spliterator<Identifier>.
	 *
	 * <p>
	 * By capturing the {@link Catalog} object at creation time, this spliterator
	 * does not need a {@link ReadContext} while it runs. It provides snapshot-at-start
	 * semantics.
	 *
	 * <p>
	 * Rather than try to make wise Spliterator design choices, which is an
	 * arcane and subtle art, we take the given one and wrap it so it iterates
	 * through entities instead of Identifiers. If the given Spliterator is a
	 * good one, then this one is at least as good; it may be better because
	 * it adds a couple of Listing-specific characteristic flags.
	 *
	 * @author pdoyle
	 */
	@RequiredArgsConstructor
	private final class CatalogLookupSpliterator implements Spliterator<E> {
		private final Spliterator<Identifier> idSpliterator;
		private final Catalog<E> catalog;

		@Override
		public boolean tryAdvance(Consumer<? super E> action) {
			return idSpliterator.tryAdvance(id -> action.accept(getOrThrow(catalog, id)));
		}

		@Override
		public Spliterator<E> trySplit() {
			Spliterator<Identifier> newIDSpliterator = idSpliterator.trySplit();
			if (newIDSpliterator == null) {
				return null;
			} else {
				return new CatalogLookupSpliterator(newIDSpliterator, catalog);
			}
		}

		@Override public long estimateSize()   { return idSpliterator.estimateSize(); }
		@Override public int characteristics() { return idSpliterator.characteristics() | NONNULL | IMMUTABLE; }
	}

	/**
	 * Gets an entry from the given <code>catalog</code>. If there is no such
	 * entry, throws {@link NonexistentReferenceException}.
	 *
	 * <p>
	 * Does not require a {@link ReadContext}.
	 */
	private <EE extends Entity> EE getOrThrow(Catalog<EE> catalog, Identifier id) {
		EE result = catalog.get(id); // Cheaper than someReference.then(id).value(), and doesn't need a ReadContext
		if (result == null) {
			throw new NonexistentReferenceException(this.catalog.then(id));
		} else {
			return result;
		}
	}

}
