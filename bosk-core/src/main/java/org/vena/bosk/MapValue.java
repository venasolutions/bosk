package org.vena.bosk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
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
	@Delegate private final Map<String, V> contents;

	@SuppressWarnings("unchecked")
	public static <VV> MapValue<VV> empty() {
		return EMPTY;
	}

	@SuppressWarnings("RedundantUnmodifiable") // Not redundant! singletonMap doesn't throw UnsupportedOperationException as much
	public static <VV> MapValue<VV> singleton(String key, VV value) {
		return new MapValue<>(unmodifiableMap(singletonMap(key, value)));
	}

	public static <VV> MapValue<VV> fromFunction(Iterable<String> keys, Function<String, VV> valueFunction) {
		LinkedHashMap<String,VV> map = new LinkedHashMap<>();
		keys.forEach(key -> addToMap(map, key, valueFunction.apply(key)));
		return new MapValue<>(unmodifiableMap(map));
	}

	public static <VV> MapValue<VV> fromOrderedMap(Map<String, VV> entries) {
		return fromEntries(entries.entrySet().iterator());
	}

	private static <VV> MapValue<VV> fromEntries(Iterator<Entry<String, VV>> entrySet) {
		LinkedHashMap<String,VV> map = new LinkedHashMap<>();
		entrySet.forEachRemaining(entry -> addToMap(map, entry.getKey(), entry.getValue()));
		return new MapValue<>(unmodifiableMap(map));
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
			LinkedHashMap<String, V> map = new LinkedHashMap<>(contents);
			map.put(name, value);
			return new MapValue<>(unmodifiableMap(map));
		}
	}

	public MapValue<V> without(String name) {
		if (containsKey(name)) {
			LinkedHashMap<String, V> map = new LinkedHashMap<>(contents);
			map.remove(name);
			return new MapValue<>(unmodifiableMap(map));
		} else {
			return this;
		}
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
}
