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
 * is accessible from {@link #domain()}.
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
	private final CatalogReference<E> domain;
	private final Set<Identifier> ids;

	public int size() { return ids.size(); }
	public boolean isEmpty() { return ids.isEmpty(); }

	@Override
	public String toString() {
		return domain + "/" + ids;
	}

	//
	// ID-based methods.  Simple and efficient, though not as type-safe as the entity-based variants.
	//

	public Collection<Identifier> ids() {
		return unmodifiableSet(ids);
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
			return new Listing<>(domain, unmodifiableSet(newIDs));
		}
	}

	public Listing<E> withoutID(Identifier id) {
		if (ids.contains(id)) {
			Set<Identifier> newValues = new LinkedHashSet<>(ids);
			newValues.remove(id);
			return new Listing<>(domain, unmodifiableSet(newValues));
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
		return new Listing<>(domain, unmodifiableSet(newIDs));
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
	// "Value" methods return entities from {@link #domain}. They require a read context
	// to call, but not afterward; for example, {@link #valueIterator} needs a read
	// context while constructing the Iterator object, but they don't need one while
	// consuming items from the Iterator.
	//

	/**
	 * @return <code>{@link #domain}().{@link CatalogReference#then(Identifier)
	 * then}(id).{@link Reference#value() value}()</code> if <code>this.{@link
	 * #containsID(Identifier) containsID}(id)</code>, or <code>null</code>
	 * otherwise.
	 * @throws NonexistentReferenceException if {@link #domain()} is nonexistent
	 * or does not contain an entity of the given <code>id</code>
	 */
	public E getValue(Identifier id) {
		if (ids.contains(id)) {
			return getOrThrow(domain.value(), id);
		} else {
			return null;
		}
	}

	public Iterator<E> valueIterator() {
		return valueIteratorImpl(this.domain.value());
	}

	public Spliterator<E> valueSpliterator() {
		return new DomainLookupSpliterator(ids.spliterator(), domain.value());
	}

	public Iterable<E> values() {
		// This whole method could just return this::valueIterator, but we can also
		// provide a better spliterator than the default one from Iterable because
		// we know a Listing qualifies for Spliterator.IMMUTABLE. And let's give
		// a nice toString too, for debugging.
		AddressableByIdentifier<E> domain = this.domain.value();
		return new Iterable<E>() {
			@Override
			public Iterator<E> iterator() {
				return valueIteratorImpl(domain);
			}

			@Override
			public Spliterator<E> spliterator() {
				return new DomainLookupSpliterator(ids.spliterator(), domain);
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
			result.put(id, getOrThrow(domain.value(), id));
		}
		return unmodifiableMap(result);
	}

	//
	// Static factory methods
	//

	public static <TT extends Entity> Listing<TT> empty(Reference<Catalog<TT>> domain) {
		return new Listing<>(CatalogReference.from(domain), emptySet());
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> domain, Identifier...ids) {
		return Listing.of(domain, Arrays.asList(ids));
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> domain, Collection<Identifier> ids) {
		return new Listing<>(CatalogReference.from(domain), new LinkedHashSet<>(ids));
	}

	public static <TT extends Entity> Listing<TT> of(Reference<Catalog<TT>> domain, Stream<Identifier> ids) {
		return Listing.of(domain, ids.collect(toList()));
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
		return new Listing<>(domain, unmodifiableSet(newIDs));
	}

	//
	// Private helpers
	//

	private Iterator<E> valueIteratorImpl(AddressableByIdentifier<E> domain) {
		Iterator<Identifier> iter = ids.iterator();
		return new Iterator<E>() {
			@Override public boolean hasNext() { return iter.hasNext(); }
			@Override public E next() { return getOrThrow(domain, iter.next()); }
		};
	}

	/**
	 * Makes a Spliterator<E> out of a Spliterator<Identifier>.
	 *
	 * <p>
	 * By capturing the {@link #domain} object at creation time, this spliterator
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
	private final class DomainLookupSpliterator implements Spliterator<E> {
		private final Spliterator<Identifier> idSpliterator;
		private final AddressableByIdentifier<E> domain;

		@Override
		public boolean tryAdvance(Consumer<? super E> action) {
			return idSpliterator.tryAdvance(id -> action.accept(getOrThrow(domain, id)));
		}

		@Override
		public Spliterator<E> trySplit() {
			Spliterator<Identifier> newIDSpliterator = idSpliterator.trySplit();
			if (newIDSpliterator == null) {
				return null;
			} else {
				return new DomainLookupSpliterator(newIDSpliterator, domain);
			}
		}

		@Override public long estimateSize()   { return idSpliterator.estimateSize(); }
		@Override public int characteristics() { return idSpliterator.characteristics() | NONNULL | IMMUTABLE; }
	}

	/**
	 * Gets an entry from the given <code>domain</code>. If there is no such
	 * entry, throws {@link NonexistentReferenceException}.
	 *
	 * <p>
	 * Does not require a {@link ReadContext}.
	 */
	private <EE extends Entity> EE getOrThrow(AddressableByIdentifier<EE> domain, Identifier id) {
		EE result = domain.get(id);
		if (result == null) {
			throw new NonexistentReferenceException(this.domain.then(id));
		} else {
			return result;
		}
	}

}
