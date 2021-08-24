package org.vena.bosk;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

@Accessors(fluent = true)
@EqualsAndHashCode
@RequiredArgsConstructor(access=AccessLevel.PRIVATE)
public final class Mapping<K extends Entity, V> implements AddressableByIdentifier<V> {
	@Getter
	private final CatalogReference<K> catalog;
	private final Map<Identifier, V> valuesById;

	public V get(Identifier id) { return valuesById.get(id); }
	public V get(K key)         { return valuesById.get(key.id()); }

	public boolean hasID(Identifier id) { return valuesById.containsKey(id); }
	public boolean hasKey(K key)        { return valuesById.containsKey(key.id()); }

	public boolean isEmpty() { return valuesById.isEmpty(); }
	public int size() { return valuesById.size(); }
	public List<Identifier> ids() { return unmodifiableList(new ArrayList<>(valuesById.keySet())); }
	public Listing<K> keys() { return new Listing<>(catalog, valuesById.keySet()); }
	public Collection<V> values() { return valuesById.values(); }
	public Set<Entry<Identifier, V>> idEntrySet() { return valuesById.entrySet(); }

	public Map<Identifier, V> asMap() { return valuesById; }

	public Stream<Entry<K, V>> valueEntryStream() {
		Catalog<K> catalogValue = catalog.value();
		return idEntrySet().stream().map(e -> new SimpleImmutableEntry<>(
			catalogValue.get(e.getKey()),
			e.getValue()));
	}

	public void forEach(BiConsumer<? super K, ? super V> action) {
		Catalog<K> keys = catalog.value();
		valuesById.forEach((id, value) -> action.accept(keys.get(id), value));
	}

	public Mapping<K,V> with(Identifier id, V value) {
		Map<Identifier, V> newMapping = new LinkedHashMap<>(this.valuesById);
		newMapping.put(id, value);
		return new Mapping<>(this.catalog, unmodifiableMap(newMapping));
	}

	public Mapping<K,V> with(K key, V value) {
		return this.with(key.id(), value);
	}

	public Mapping<K,V> updatedWith(Identifier id, Supplier<V> valueIfAbsent, UnaryOperator<V> valueIfPresent) {
		V existing = valuesById.get(id);
		V replacement;
		if (existing == null) {
			replacement = valueIfAbsent.get();
		} else {
			replacement = valueIfPresent.apply(existing);
		}
		return this.with(id, replacement);
	}

	public Mapping<K,V> without(Identifier id) {
		Map<Identifier, V> newMapping = new LinkedHashMap<>(this.valuesById);
		newMapping.remove(id);
		return new Mapping<>(this.catalog, unmodifiableMap(newMapping));
	}

	public Mapping<K,V> without(K key) {
		return this.without(key.id());
	}

	/**
	 * If you get type inference errors with this one, try specifying the value class
	 * with {@link #empty(Reference, Class)}.
	 */
	public static <KK extends Entity,VV> Mapping<KK,VV> empty(Reference<Catalog<KK>> catalog) {
		return new Mapping<>(CatalogReference.from(catalog), emptyMap());
	}

	public static <KK extends Entity,VV> Mapping<KK,VV> empty(Reference<Catalog<KK>> catalog, Class<VV> ignored) {
		return empty(catalog);
	}

	public static <KK extends Entity, VV> Mapping<KK,VV> of(Reference<Catalog<KK>> catalog, Identifier id, VV value) {
		return new Mapping<>(CatalogReference.from(catalog), singletonMap(id, value));
	}

	public static <KK extends Entity, VV> Mapping<KK,VV> of(Reference<Catalog<KK>> catalog, KK key, VV value) {
		return Mapping.of(catalog, key.id(), value);
	}

	public static <KK extends Entity,VV> Mapping<KK,VV> fromOrderedMap(Reference<Catalog<KK>> catalog, Map<Identifier, VV> contents) {
		return new Mapping<>(CatalogReference.from(catalog), unmodifiableMap(new LinkedHashMap<>(contents)));
	}

	public static <KK extends Entity,VV> Mapping<KK,VV> fromFunction(Reference<Catalog<KK>> catalog, Stream<Identifier> ids, Function<Identifier, VV> function) {
		LinkedHashMap<Identifier,VV> map = new LinkedHashMap<>();
		ids.forEachOrdered(id -> map.put(id, function.apply(id)));
		return new Mapping<>(CatalogReference.from(catalog), unmodifiableMap(map));
	}

	@Override
	public String toString() {
		return catalog + "/" + valuesById;
	}

}
