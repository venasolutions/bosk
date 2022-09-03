package io.vena.bosk;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class MapValueTest extends AbstractBoskTest {

	private static Stream<Arguments> mapArguments() {
		return Stream.of(
			mapValueCase(emptyMap(), MapValue.empty(), "sample"),
			mapValueCase(singletonMap("key", "value"), MapValue.singleton("key", "value"), "sample"),
			mapValueCase(singletonMap("", "value"), MapValue.singleton("", "value"), "sample"),
			fromMapCase(emptyMap(), "sample"),
			fromMapCase(singletonMap("key1", "value1"), "sample"),
			fromMapCase(singletonMap("key1", new NoEqualsOrHashCode("value1")), new NoEqualsOrHashCode("value2")),
			fromFunctionCase(asList("key1", "key2"), Identifier::from, Identifier.unique("sample")),
			fromFunctionCase(asList("key1", "key1"), key->"Same object", Identifier.unique("sample"))
		);
	}

	private static <V> Arguments mapValueCase(Map<String, V> map, MapValue<V> mapValue, V sampleValue) {
		try {
			assertFalse(map.containsValue(sampleValue), "Hey, fix your test case so the sample value is not present in the map!");
		} catch (UnsupportedOperationException e) {
			// Ok, fine, just so long as it doesn't return true.
		}
		return Arguments.of(unmodifiableMap(map), mapValue, sampleValue);
	}

	private static <V> Arguments fromMapCase(Map<String, V> map, V sampleValue) {
		return mapValueCase(map, MapValue.fromOrderedMap(map), sampleValue);
	}

	private static <V> Arguments fromFunctionCase(Iterable<String> keys, Function<String, V> function, V sampleValue) {
		Map<String, V> map = new LinkedHashMap<>();
		keys.forEach(key -> map.put(key, function.apply(key)));
		return mapValueCase(map, MapValue.fromFunction(keys, function), sampleValue);
	}

	@RequiredArgsConstructor
	private static class NoEqualsOrHashCode {
		private final String field;

		@Override public int hashCode() { throw new UnsupportedOperationException(); }

		@Override public boolean equals(Object obj) {
			if (this == obj) {
				// Required by the Object.equals spec.
				// Some libraries (like LinkedHashMap) don't even call equals if the
				// objects are the same; others (like SingletonMap) do.
				return true;
			} else {
				throw new UnsupportedOperationException();
			}
		}
	}

	/**
	 * pitest currently treats initialization errors as survival.
	 * We want initialization errors to count as test failures, so we run the initialization
	 * code in this test and assert that it ran without exceptions and produced some test cases.
	 */
	@Test
	void helpMutationTester() {
		try {
			mapArguments().collect(toList());
		} catch (Throwable t) {
			fail();
		}
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void testEquals(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertEquals(MapValue.fromOrderedMap(map), mapValue);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void testHashCode(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		try {
			int expected = MapValue.fromOrderedMap(map).hashCode();
			assertEquals(expected, mapValue.hashCode());
		} catch (UnsupportedOperationException e) {
			assertThrows(e.getClass(), mapValue::hashCode);
		}
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void size(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertEquals(map.size(), mapValue.size());
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void isEmpty(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertEquals(map.isEmpty(), mapValue.isEmpty());
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void containsKey(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		for (String key: map.keySet()) {
			assertEquals(map.containsKey(key), mapValue.containsKey(key));
		}
		assertFalse(mapValue.containsKey(NONEXISTENT_KEY));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void containsValue(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		for (V value: map.values()) {
			assertEqualsIfSupported(() -> map.containsValue(value), () -> mapValue.containsValue(value));
		}
		assertEqualsIfSupported(() -> map.containsValue(sampleValue), () -> mapValue.containsValue(sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void get(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		for (String key: map.keySet()) {
			assertEquals(map.get(key), mapValue.get(key));
		}
		assertNull(mapValue.get(NONEXISTENT_KEY));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void put(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertThrows(UnsupportedOperationException.class, () -> mapValue.put(NONEXISTENT_KEY, sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void remove(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), mapValue::remove);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void putAll(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertThrows(UnsupportedOperationException.class, () -> mapValue.putAll(map));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void clear(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertThrows(UnsupportedOperationException.class, mapValue::clear);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void keySet(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertEquals(map.keySet(), mapValue.keySet());
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void values(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		// Some maps return a list, and some a set. We don't care,
		// as long as they have the same values in the same order.
		Collection<V> expected = map.values();
		Collection<V> actual = mapValue.values();
		assertEquals(new ArrayList<>(expected), new ArrayList<>(actual));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void entrySet(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertEquals(map.entrySet(), mapValue.entrySet());
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void getOrDefault(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		for (String key: map.keySet()) {
			assertEquals(map.getOrDefault(key, sampleValue), mapValue.getOrDefault(key, sampleValue));
		}
		assertEquals(map.getOrDefault(NONEXISTENT_KEY, sampleValue), mapValue.getOrDefault(NONEXISTENT_KEY, sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void forEach(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		List<SimpleEntry<String, V>> expected = new ArrayList<>();
		map.forEach((key, value) -> expected.add(new SimpleEntry<>(key, value)));
		List<SimpleEntry<String, V>> actual = new ArrayList<>();
		mapValue.forEach((key, value) -> actual.add(new SimpleEntry<>(key, value)));
		assertEquals(expected, actual);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void replaceAll(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertThrows(UnsupportedOperationException.class, () -> mapValue.replaceAll((k,v)->sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void putIfAbsent(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.putIfAbsent(key, sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void testRemove(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), mapValue::remove);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void replace(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.replace(key, sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void computeIfAbsent(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.computeIfAbsent(key, k -> sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void computeIfPresent(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.computeIfPresent(key, (k,v) -> sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void compute(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.compute(key, (k,v) -> sampleValue));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void merge(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertUnsupportedForAllKeys(map.keySet(), key -> mapValue.merge(key, sampleValue, (v1,v2)->v1));
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void with_actsLikeLinkedHashMapPut_nonexistentKey(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		Map<String,V> mapWithNewEntry = new LinkedHashMap<>(map);
		mapWithNewEntry.put(NONEXISTENT_KEY, sampleValue);
		MapValue<V> mapValueWithNewEntry = mapValue.with(NONEXISTENT_KEY, sampleValue);
		assertEquals(mapWithNewEntry, mapValueWithNewEntry);
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void with_actsLikeLinkedHashMapPut_existingKey(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		map.forEach((key,value) -> {
			Map<String,V> mapWithNewEntry = new LinkedHashMap<>(map);
			mapWithNewEntry.put(key, sampleValue);
			MapValue<V> mapValueWithNewEntry = mapValue.with(key, sampleValue);
			assertEquals(mapWithNewEntry, mapValueWithNewEntry);
		});
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void without_actsLikeLinkedHashMapRemove(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		List<String> keys = new ArrayList<>(map.keySet());
		for (ListIterator<String> outer = keys.listIterator(); outer.hasNext(); ) {
			outer.next();
			Map<String,V> modifiedMap = new LinkedHashMap<>(map);
			MapValue<V> modifiedMapValue = mapValue;
			for (ListIterator<String> inner = keys.listIterator(outer.nextIndex()); inner.hasNext(); ) {
				String keyToRemove = inner.next();
				modifiedMap.remove(keyToRemove);
				modifiedMapValue = modifiedMapValue.without(keyToRemove);
			}
			assertEquals(modifiedMap, modifiedMapValue);
		}
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void with_returnsOriginalIfNoEffect(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		map.forEach((key,value) -> {
			assertEquals(mapValue, mapValue.with(key, value));
			assertSame(mapValue, mapValue.with(key, mapValue.get(key)));
		});
	}

	@ParameterizedTest
	@MethodSource("mapArguments")
	<V> void without_returnsOriginalIfNoEffect(Map<String,V> map, MapValue<V> mapValue, V sampleValue) {
		assertSame(mapValue, mapValue.without(NONEXISTENT_KEY));
	}

	@Test
	void testBad_duplicateKeysFromFunction() {
		assertThrows(IllegalArgumentException.class, () -> MapValue.fromFunction(asList("dup", "dup"), Identifier::unique));
	}

	@Test
	void testBad_nullKeyFromFunction() {
		assertThrows(NullPointerException.class, () -> MapValue.fromFunction(singletonList(null), s ->"Value"));
	}

	@Test
	void testBad_nullValueFromFunction() {
		assertThrows(NullPointerException.class, () -> MapValue.fromFunction(singletonList("key"), s ->null));
	}

	@Test
	void testBad_nullKeyFromMap() {
		Map<String, ?> map = singletonMap(null, "value");
		assertThrows(NullPointerException.class, () -> MapValue.fromOrderedMap(map));
	}

	@Test
	void testBad_nullValueFromMap() {
		Map<String, ?> map = singletonMap("key", null);
		assertThrows(NullPointerException.class, () -> MapValue.fromOrderedMap(map));
	}

	private void assertUnsupportedForAllKeys(Iterable<String> keys, Consumer<String> action) {
		assertThrows(UnsupportedOperationException.class, () -> action.accept(NONEXISTENT_KEY));
		keys.forEach(key ->
			assertThrows(UnsupportedOperationException.class, () -> action.accept(key)));
	}

	private static <T> void assertEqualsIfSupported(Supplier<T> expected, Supplier<T> actual) {
		try {
			T expectedResult = expected.get();
			assertEquals(expectedResult, actual.get());
		} catch (UnsupportedOperationException e) {
			assertThrows(e.getClass(), actual::get);
		}
	}

	/**
	 * Don't anyone ever use this as an actual key in a test case, man.
	 */
	public static final String NONEXISTENT_KEY = "nonexistent";

}
