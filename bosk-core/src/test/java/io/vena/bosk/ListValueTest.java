package io.vena.bosk;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListValueTest {

	static class ArrayArgumentProvider implements ArgumentsProvider {

		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
			return Stream.of(
				strings(),
				strings("a"),
				strings("a", null),
				strings(null, "a"),
				strings("a", null, "b", null, "c"),
				strings("a","b","b","a"));
		}

		private Arguments strings(String...strings) {
			return Arguments.of((Object)strings);
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testOf(String[] contents) {
		assertEquals(asList(contents), ListValue.of(contents));
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testHashCode(String[] contents) {
		assertEquals(asList(contents).hashCode(), ListValue.of(contents).hashCode());
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testSize(String[] contents) {
		assertEquals(contents.length, ListValue.of(contents).size());
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testIsEmpty(String[] contents) {
		assertEquals(contents.length == 0, ListValue.of(contents).isEmpty());
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testListIterator(String[] contents) {
		assertThrows(IndexOutOfBoundsException.class, ()->ListValue.of(contents).listIterator(-1));
		assertThrows(IndexOutOfBoundsException.class, ()->ListValue.of(contents).listIterator(contents.length+1));

		checkListIterators(asList(contents).listIterator(), ListValue.of(contents).listIterator());
		checkListIterators(asList(contents).listIterator(), ListValue.of(contents).listIterator(0));
		for (int i = 0; i <= contents.length; i++) {
			try {
				checkListIterators(asList(contents).listIterator(i), ListValue.of(contents).listIterator(i));
			} catch (AssertionError e) {
				throw new AssertionError("At " + Arrays.toString(contents) + " @ " + i + ": " + e.getMessage(), e);
			}
		}

		// Now check that iterator() returns the same as listIterator()
		checkIterators(ListValue.of(contents).listIterator(), ListValue.of(contents).iterator());
	}

	@Test
	void differentArrayTypes_stillEqual() {
		Object[] objects = new Object[]{"string1", "string2"};
		ListValue<?> fromObjects = ListValue.of(objects);
		ListValue<?> fromStrings = ListValue.of("string1", "string2");
		assertEquals(fromObjects, fromStrings);
	}

	private void checkIterators(Iterator<String> expected, Iterator<String> actual) {
		while (expected.hasNext()) {
			assertTrue(actual.hasNext());
			String a = actual.next();
			String e = expected.next();
			assertSame(a, e);
			assertThrows(UnsupportedOperationException.class, actual::remove);
		}
		assertThrows(NoSuchElementException.class, actual::next);
		assertEquals(expected.hasNext(), actual.hasNext());
	}

	private void checkListIterators(ListIterator<String> expected, ListIterator<String> actual) {
		while (expected.hasNext()) {
			checkListIteratorState(expected, actual);
			String a = actual.next();
			String e = expected.next();
			assertSame(a, e);
			assertThrows(UnsupportedOperationException.class, actual::remove);
			assertThrows(UnsupportedOperationException.class, ()->actual.set("x"));
			assertThrows(UnsupportedOperationException.class, ()->actual.add("x"));
		}
		assertThrows(NoSuchElementException.class, actual::next);
		checkListIteratorState(expected, actual);
		while (expected.hasPrevious()) {
			checkListIteratorState(expected, actual);
			String a = actual.previous();
			String e = expected.previous();
			assertSame(a, e);
		}
		checkListIteratorState(expected, actual);
		assertThrows(NoSuchElementException.class, actual::previous);
	}

	private void checkListIteratorState(ListIterator<String> expected, ListIterator<String> actual) {
		assertEquals(expected.hasPrevious(), actual.hasPrevious());
		assertEquals(expected.hasNext(), actual.hasNext());
		assertEquals(expected.nextIndex(), actual.nextIndex());
		assertEquals(expected.previousIndex(), actual.previousIndex());
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testGet(String[] contents) {
		List<String> expected = asList(contents);
		ListValue<String> actual = ListValue.of(contents);
		for (int i = 0; i < expected.size(); i++) {
			assertSame(actual.get(i), expected.get(i));
		}
		assertThrows(IndexOutOfBoundsException.class, ()->actual.get(-1));
		assertThrows(IndexOutOfBoundsException.class, ()->actual.get(contents.length));
	}

	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testToArray(String[] contents) {
		ListValue<String> actual = ListValue.of(contents);
		Object[] copy = actual.toArray();
		assertEquals(copy.length, contents.length);
		for (int i = 0; i < contents.length; i++) {
			assertSame(contents[i], copy[i]);

			// Make sure mutations don't affect ListValue
			assertSame(contents[i], actual.get(i));
			copy[i] = copy[i] + " suffix";
			assertSame(contents[i], actual.get(i));
		}
	}

	@Test
	void testAdd() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").add("b"));
	}

	@Test
	void testRemove() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").remove("a"));
	}

	@Test
	void testAddAllCollectionOfQextendsT() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").addAll(singletonList("b")));
	}

	@Test
	void testAddAllIntCollectionOfQextendsT() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").addAll(0, singletonList("b")));
	}

	@Test
	void testRemoveAll() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").removeAll(singletonList("a")));
	}

	@Test
	void testRetainAll() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").retainAll(singletonList("b")));
	}

	@Test
	void testClear() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").clear());
	}

	@Test
	void testSet() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").set(0, "b"));
	}

	@Test
	void testAddIntT() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").add(0, "b"));
	}

	@Test
	void testRemoveInt() {
		assertThrows(UnsupportedOperationException.class, ()->ListValue.of("a").remove(0));
	}

	@SuppressWarnings("unlikely-arg-type")
	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testContains(String[] contents) {
		ListValue<String> actual = ListValue.of(contents);
		for (String s: contents) {
			assertTrue(actual.contains(s));
			if (s != null) {
				// Check that it's using equals instead of ==
				assertTrue(actual.contains(new String(s)));
			}
		}
		assertFalse(actual.contains(123L));
		assertFalse(actual.contains("Some nonexistent string"));
	}

	@SuppressWarnings("unlikely-arg-type")
	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testContainsAll(String[] contents) {
		ListValue<String> actual = ListValue.of(contents);
		assertTrue(actual.containsAll(Collections.<String>emptyList()));
		assertTrue(actual.containsAll(asList(contents)));
		assertFalse(actual.containsAll(asList(123L)));
		assertFalse(actual.containsAll(asList("nonexistent1", "nonexistent2")));
	}

	@SuppressWarnings("unlikely-arg-type")
	@ParameterizedTest
	@ArgumentsSource(ArrayArgumentProvider.class)
	void testIndexOf(String[] contents) {
		List<String> expected = asList(contents);
		ListValue<String> actual = ListValue.of(contents);
		assertEquals(expected.indexOf(null), actual.indexOf(null));
		assertEquals(expected.lastIndexOf(null), actual.lastIndexOf(null));
		for (String needle : contents) {
			assertEquals(expected.indexOf(needle), actual.indexOf(needle));
			assertEquals(expected.lastIndexOf(needle), actual.lastIndexOf(needle));
		}
		assertEquals(-1, actual.indexOf(123L));
		assertEquals(-1, actual.lastIndexOf(123L));
		assertEquals(-1, actual.indexOf("Some nonexistent string"));
		assertEquals(-1, actual.lastIndexOf("Some nonexistent string"));
	}

	@Test
	void testToArrayTTArray() {
		// TODO
	}

	@Test
	void testSubList() {
		// TODO
	}

}
