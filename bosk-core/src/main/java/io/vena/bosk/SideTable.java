package io.vena.bosk;

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
public final class SideTable<K extends Entity, V> implements EnumerableByIdentifier<V> {
	@Getter
	private final CatalogReference<K> domain;
	private final Map<Identifier, V> valuesById;

	public V get(Identifier id) { return valuesById.get(id); }
	public V get(K key)         { return valuesById.get(key.id()); }

	public boolean hasID(Identifier id) { return valuesById.containsKey(id); }
	public boolean hasKey(K key)        { return valuesById.containsKey(key.id()); }

	public boolean isEmpty() { return valuesById.isEmpty(); }
	public int size() { return valuesById.size(); }
	public List<Identifier> ids() { return unmodifiableList(new ArrayList<>(valuesById.keySet())); }
	public Listing<K> keys() { return new Listing<>(domain, valuesById.keySet()); }
	public Collection<V> values() { return valuesById.values(); }
	public Set<Entry<Identifier, V>> idEntrySet() { return valuesById.entrySet(); }

	public Map<Identifier, V> asMap() { return valuesById; }

	public Stream<Entry<K, V>> valueEntryStream() {
		AddressableByIdentifier<K> domainValue = domain.value();
		return idEntrySet().stream().map(e -> new SimpleImmutableEntry<>(
			domainValue.get(e.getKey()),
			e.getValue()));
	}

	public void forEach(BiConsumer<? super K, ? super V> action) {
		AddressableByIdentifier<K> domainValue = domain.value();
		valuesById.forEach((id, value) -> action.accept(domainValue.get(id), value));
	}

	public SideTable<K,V> with(Identifier id, V value) {
		Map<Identifier, V> newMap = new LinkedHashMap<>(this.valuesById);
		newMap.put(id, value);
		return new SideTable<>(this.domain, unmodifiableMap(newMap));
	}

	public SideTable<K,V> with(K key, V value) {
		return this.with(key.id(), value);
	}

	public SideTable<K,V> updatedWith(Identifier id, Supplier<V> valueIfAbsent, UnaryOperator<V> valueIfPresent) {
		V existing = valuesById.get(id);
		V replacement;
		if (existing == null) {
			replacement = valueIfAbsent.get();
		} else {
			replacement = valueIfPresent.apply(existing);
		}
		return this.with(id, replacement);
	}

	public SideTable<K,V> without(Identifier id) {
		Map<Identifier, V> newMap = new LinkedHashMap<>(this.valuesById);
		newMap.remove(id);
		return new SideTable<>(this.domain, unmodifiableMap(newMap));
	}

	public SideTable<K,V> without(K key) {
		return this.without(key.id());
	}

	/**
	 * If you get type inference errors with this one, try specifying the value class
	 * with {@link #empty(Reference, Class)}.
	 */
	public static <KK extends Entity,VV> SideTable<KK,VV> empty(Reference<Catalog<KK>> domain) {
		return new SideTable<>(CatalogReference.from(domain), emptyMap());
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> empty(Reference<Catalog<KK>> domain, Class<VV> ignored) {
		return empty(domain);
	}

	public static <KK extends Entity, VV> SideTable<KK,VV> of(Reference<Catalog<KK>> domain, Identifier id, VV value) {
		return new SideTable<>(CatalogReference.from(domain), singletonMap(id, value));
	}

	public static <KK extends Entity, VV> SideTable<KK,VV> of(Reference<Catalog<KK>> domain, KK key, VV value) {
		return SideTable.of(domain, key.id(), value);
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> fromOrderedMap(Reference<Catalog<KK>> domain, Map<Identifier, VV> contents) {
		return new SideTable<>(CatalogReference.from(domain), unmodifiableMap(new LinkedHashMap<>(contents)));
	}

	public static <KK extends Entity,VV> SideTable<KK,VV> fromFunction(Reference<Catalog<KK>> domain, Stream<Identifier> keyIDs, Function<Identifier, VV> function) {
		LinkedHashMap<Identifier,VV> map = new LinkedHashMap<>();
		keyIDs.forEachOrdered(id -> map.put(id, function.apply(id)));
		return new SideTable<>(CatalogReference.from(domain), unmodifiableMap(map));
	}

	@Override
	public String toString() {
		return domain + "/" + valuesById;
	}

}
