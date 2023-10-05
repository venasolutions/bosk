package io.vena.bosk;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.pcollections.OrderedPMap;

import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;

/**
 * An immutable {@link Map} that can be used in a {@link Bosk}.
 *
 * <p>
 * This is a "pseudo-primitive value" in the sense that there's no way to make a {@link Reference}
 * to an entry within a {@link MapValue}: the whole map is updated and deleted as a unit.
 *
 * <p>
 * This is an "escape hatch" for when you just want to have a map with
 * {@link String} keys that persists in a Bosk.
 * For most purposes, {@link SideTable} is more appropriate.
 *
 * <p>
 * The entry values in the list must still be valid Bosk datatypes. This is not a
 * magic way to put arbitrary data structures into a Bosk.
 *
 * @author pdoyle
 */
@RequiredArgsConstructor(access= AccessLevel.PRIVATE)
@EqualsAndHashCode
public final class MapValue<V> implements Map<String, V> {
	private final OrderedPMap<String, V> contents;

	@SuppressWarnings("unchecked")
	public static <VV> MapValue<VV> empty() {
		return EMPTY;
	}

	public static <VV> MapValue<VV> singleton(String key, VV value) {
		return new MapValue<>(OrderedPMap.singleton(key, value));
	}

	public static <VV> MapValue<VV> fromFunction(Iterable<String> keys, Function<String, VV> valueFunction) {
		LinkedHashMap<String,VV> map = new LinkedHashMap<>();
		keys.forEach(key -> addToMap(map, key, valueFunction.apply(key)));
		return new MapValue<>(OrderedPMap.from(map));
	}

	public static <VV> MapValue<VV> fromOrderedMap(Map<String, VV> entries) {
		return fromEntries(entries.entrySet().iterator());
	}

	private static <VV> MapValue<VV> fromEntries(Iterator<Entry<String, VV>> entrySet) {
		LinkedHashMap<String,VV> map = new LinkedHashMap<>();
		entrySet.forEachRemaining(entry -> addToMap(map, entry.getKey(), entry.getValue()));
		return new MapValue<>(OrderedPMap.from(map));
	}

	private static <VV> void addToMap(LinkedHashMap<String, VV> map, String key, VV newValue) {
		VV existingValue = map.put(requireNonNull(key), requireNonNull(newValue));
		if (existingValue != null && existingValue != newValue) {
			throw new IllegalArgumentException("Two different values for the same key \"" + key + "\"");
		}
	}

	public MapValue<V> with(String name, V value) {
		if (get(name) == value) {
			return this;
		} else {
			return new MapValue<>(contents.plus(name, value));
		}
	}

	public MapValue<V> without(String name) {
		if (containsKey(name)) {
			return new MapValue<>(contents.minus(name));
		} else {
			return this;
		}
	}

	public MapValue<V> withAll(Map<String, ? extends V> m) {
		return new MapValue<>(contents.plusAll(m));
	}

	@Override
	public String toString() {
		return contents.toString();
	}

	/**
	 * Note that if we use emptyMap on its own, it will not throw UnsupportedOperationException
	 * under the same conditions as unmodifiableMap. Hence, this guy has emptyMap wrapped in
	 * an unmodifiableMap so it's exception-compatible.
	 */
	@SuppressWarnings("rawtypes")
	private static final MapValue EMPTY = fromOrderedMap(emptyMap());

	///////////////////////
	//
	//  Delegated
	//

	@Override public int size() { return contents.size(); }
	@Override public boolean isEmpty() { return contents.isEmpty(); }
	@Override public boolean containsKey(Object key) { return contents.containsKey(key); }
	@Override public boolean containsValue(Object value) { return contents.containsValue(value); }
	@Override public V get(Object key) { return contents.get(key); }
	@Override public Set<String> keySet() { return contents.keySet(); }
	@Override public Collection<V> values() { return contents.values(); }
	@Override public Set<Entry<String, V>> entrySet() { return contents.entrySet(); }

	@Override public V put(String key, V value) { throw new UnsupportedOperationException(); }
	@Override public V remove(Object key) { throw new UnsupportedOperationException(); }
	@Override public void putAll(Map<? extends String, ? extends V> m) { throw new UnsupportedOperationException(); }
	@Override public void clear() { throw new UnsupportedOperationException(); }

	@Override public void replaceAll(BiFunction<? super String, ? super V, ? extends V> function) { throw new UnsupportedOperationException(); }
	@Override public V putIfAbsent(String key, V value) { throw new UnsupportedOperationException(); }
	@Override public boolean remove(Object key, Object value) { throw new UnsupportedOperationException(); }
	@Override public boolean replace(String key, V oldValue, V newValue) { throw new UnsupportedOperationException(); }
	@Override public V replace(String key, V value) { throw new UnsupportedOperationException(); }
	@Override public V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) { throw new UnsupportedOperationException(); }
	@Override public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) { throw new UnsupportedOperationException(); }
	@Override public V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) { throw new UnsupportedOperationException(); }
	@Override public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) { throw new UnsupportedOperationException(); }
}
