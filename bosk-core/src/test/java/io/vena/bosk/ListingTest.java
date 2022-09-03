package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NonexistentReferenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingTest {

	static class ListingArgumentProvider implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
			Stream<List<TestEntity>> childrenStream = idStreams()
					.map(Stream::distinct)
					.map(stream -> stream.map(id -> new TestEntity(Identifier.from(id), Catalog.empty()))
							.collect(toList()));
			return childrenStream
					.map(children -> {
						TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
						Bosk<TestEntity> bosk = new Bosk<>("Test Bosk", TestEntity.class, root, Bosk::simpleDriver);
						CatalogReference<TestEntity> catalog;
						try {
							catalog = bosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.children));
						} catch (InvalidTypeException e) {
							throw new AssertionError(e);
						}
						Listing<TestEntity> listing = Listing.of(catalog, children.stream().map(TestEntity::id));
						return Arguments.of(listing, children, bosk);
					});
		}
	}

	static class IDListArgumentProvider implements ArgumentsProvider {
		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
			TestEntity child = new TestEntity(Identifier.unique("child"), Catalog.empty());
			List<TestEntity> children = singletonList(child);
			TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
			Bosk<TestEntity> bosk = new Bosk<>("Test Bosk", TestEntity.class, root, Bosk::simpleDriver);
			CatalogReference<TestEntity> childrenRef = bosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.children));
			return idStreams().map(list -> Arguments.of(list.map(Identifier::from).collect(toList()), childrenRef, bosk));
		}

	}

	public static Stream<Stream<String>> idStreams() {
		return Stream.of(
				Stream.of(),
				Stream.of("a"),
				Stream.of("a", "b", "c"),
				Stream.of("a", "a", "a"),
				Stream.of("a", "b", "b", "a"),
				LongStream.range(1, 100).mapToObj(Long::toString),

				// Hash collisions
				Stream.of("Aa", "BB"),
				Stream.of("Aa", "BB", "Aa")
		);
	}

	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	@FieldNameConstants
	@EqualsAndHashCode(callSuper = false) // handy for testing
	public static class TestEntity implements Entity {
		Identifier id;
		@EqualsAndHashCode.Exclude Catalog<TestEntity> children;
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testGet(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) throws InvalidTypeException {
		try (val context = bosk.readContext()) {
			for (TestEntity child: children) {
				TestEntity actual = listing.getValue(child.id());
				assertSame(child, actual, "All expected entities should be present in the Listing");
				TestEntity expected = listing.domain().then(TestEntity.class, child.id().toString()).value();
				assertSame(expected, actual, "The definition of Listing.get should hold");
			}
			Identifier nonexistent = Identifier.unique("nonexistent");
			assertNull(listing.getValue(nonexistent), "Identifier missing from listing returns null");
			Listing<TestEntity> danglingRef = listing.withID(nonexistent);
			assertThrows(NonexistentReferenceException.class, () -> danglingRef.getValue(nonexistent), "Identifier missing from catalog throws");
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testIterator(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Iterator<TestEntity> expected = children.iterator();
		Iterator<TestEntity> actual;
		try (val context = bosk.readContext()) {
			// ReadContext is needed only when creating the iterator
			actual = listing.valueIterator();
		}
		assertEquals(expected.hasNext(), actual.hasNext());
		while (expected.hasNext()) {
			assertSame(expected.next(), actual.next());
			assertEquals(expected.hasNext(), actual.hasNext());
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testIdStream(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Iterator<Identifier> expected = children.stream().map(TestEntity::id).iterator();
		listing.idStream().forEachOrdered(actual -> assertSame(expected.next(), actual));
		assertFalse(expected.hasNext(), "No extra elements");
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testStream(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Iterator<TestEntity> expected = children.iterator();
		Stream<TestEntity> stream;
		try (val context = bosk.readContext()) {
			// ReadContext is needed only when creating the stream
			stream = listing.valueStream();
		}
		stream.forEachOrdered(actual -> assertSame(expected.next(), actual));
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testAsCollection(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Collection<TestEntity> actual;
		try (val context = bosk.readContext()) {
			// ReadContext is needed only when creating the collection
			actual = listing.valueList();
		}
		assertSameElements(children, actual);
	}

	private <T> void assertSameElements(Collection<T> expected, Collection<T> actual) {
		Iterator<T> eIter = expected.iterator();
		Iterator<T> aIter = actual.iterator();
		assertEquals(eIter.hasNext(), aIter.hasNext());
		while (eIter.hasNext()) {
			assertSame(eIter.next(), aIter.next()); // Stronger than assertEquals
			assertEquals(eIter.hasNext(), aIter.hasNext());
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testSpliterator(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		HashMap <Identifier, Integer> countMap = new HashMap<>();
		HashMap <Identifier, Integer> goodMap = new HashMap<>();

		for (Identifier i : listing.ids()) {
			goodMap.put(i, goodMap.getOrDefault(i, -1)+1);
		}

		Spliterator<TestEntity> newSplit;
		try (val context = bosk.readContext()) {
			newSplit = listing.values().spliterator();
		}

		newSplit.forEachRemaining(e -> countMap.put(e.id , countMap.getOrDefault(e.id(), -1) + 1));

		Spliterator<TestEntity> splitted = newSplit.trySplit();
		if (splitted != null) {
			splitted.forEachRemaining(e -> countMap.put(e.id , countMap.getOrDefault(e.id(), -1)+1));
		}

		assertTrue((newSplit.characteristics() & Spliterator.IMMUTABLE) != 0);
		assertEquals(goodMap, countMap);
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testSize(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(distinctEntities(children).size(), listing.size());
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testIsEmpty(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(children.isEmpty(), listing.isEmpty());
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testIds(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		assertEquals(distinctEntityIDs(children), listing.ids());
	}

	@Test
	void testEmpty() throws InvalidTypeException {
		TestEntity child = new TestEntity(Identifier.unique("child"), Catalog.empty());
		List<TestEntity> children = singletonList(child);
		TestEntity root = new TestEntity(Identifier.unique("parent"), Catalog.of(children));
		Bosk<TestEntity> bosk = new Bosk<>("Test Bosk", TestEntity.class, root, Bosk::simpleDriver);
		CatalogReference<TestEntity> childrenRef = bosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.children));

		Listing<TestEntity> actual = Listing.empty(childrenRef);
		assertTrue(actual.isEmpty());
		assertEquals(0, actual.size());

		Iterator<TestEntity> iterator;
		try (val context = bosk.readContext()) {
			// iterator() needs a ReadContext at creation time
			iterator = actual.valueIterator();
		}
		assertFalse(iterator.hasNext());
	}

	@ParameterizedTest
	@ArgumentsSource(IDListArgumentProvider.class)
	void testOfReferenceOfCatalogOfTTIdentifierArray(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids.toArray(new Identifier[0]));
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@ArgumentsSource(IDListArgumentProvider.class)
	void testOfReferenceOfCatalogOfTTCollectionOfIdentifier(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids);
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@ArgumentsSource(IDListArgumentProvider.class)
	void testOfReferenceOfCatalogOfTTStreamOfIdentifier(List<Identifier> ids, CatalogReference<TestEntity> childrenRef, Bosk<TestEntity> bosk) {
		// No ReadContext required
		Listing<TestEntity> actual = Listing.of(childrenRef, ids.stream());
		assertSameElements(distinctIDs(ids), actual.ids());
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testWithID(Listing<TestEntity> originalListing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		// Should match behaviour of LinkedHashSet
		LinkedHashSet<Identifier> exemplar = new LinkedHashSet<>(children.size());
		children.forEach(child -> exemplar.add(child.id()));
		for (TestEntity child: children) {
			Identifier id = child.id();
			Listing<TestEntity> actual = originalListing.withID(id);
			exemplar.add(id);
			assertEquals(exemplar, actual.ids());
			assertEquals(originalListing, actual, "Re-adding existing children makes no difference");
		}

		Identifier newID = Identifier.unique("nonexistent");
		Listing<TestEntity> after = originalListing.withID(newID);
		exemplar.add(newID);
		assertEquals(exemplar, after.ids());

		for (TestEntity child: children) {
			Listing<TestEntity> actual = after.withID(child.id());
			assertEquals(after, actual, "Re-adding existing children makes no difference");
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testWithEntity(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		// No ReadContext required
		// Should match behaviour of testWithIdentifier, and therefore (transitively) LinkedHashMap
		Listing<TestEntity> expected = listing;
		Listing<TestEntity> actual = listing;
		for (TestEntity child: children) {
			expected = expected.withID(child.id());
			actual = actual.withEntity(child);
			assertEquals(expected, actual, "Re-adding existing children makes no difference");
		}

		TestEntity newEntity = new TestEntity(Identifier.unique("nonexistent"), Catalog.empty());
		expected = expected.withID(newEntity.id());
		actual = actual.withEntity(newEntity);
		assertEquals(expected, actual);

		for (TestEntity child: children) {
			expected = expected.withID(child.id());
			actual = actual.withEntity(child);
			assertEquals(expected, actual);
		}
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testWithAllIDs(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Identifier id1 = Identifier.unique("nonexistent");
		Identifier id2 = Identifier.unique("nonexistent2");
		Listing<TestEntity> actual = listing.withAllIDs(Stream.of(id1, id2));
		Listing<TestEntity> expected = listing;

		expected = expected.withID(id1).withID(id2);
		assertEquals(expected, actual);

	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testWithoutEntity(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		TestEntity newEntity = new TestEntity(Identifier.unique("existent"), Catalog.empty());
		TestEntity nonexistent = new TestEntity(Identifier.unique("nonexistent"), Catalog.empty());

		Listing<TestEntity> actual = listing.withEntity(newEntity);
		assertNotEquals(actual, listing);
		actual = actual.withoutEntity(newEntity);
		assertEquals(listing, actual);
		assertEquals(listing, actual.withoutEntity(nonexistent));

	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testWithoutID(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) {
		Identifier unique = Identifier.unique("existent");
		Identifier nonexistent = Identifier.unique("nonexistent");

		TestEntity newEntity = new TestEntity(unique, Catalog.empty());
		Listing<TestEntity> actual = listing.withEntity(newEntity);
		assertNotEquals(actual, listing);
		actual = actual.withoutID(unique);
		assertEquals(listing, actual);
		assertEquals(listing, actual.withoutID(nonexistent));
	}

	@ParameterizedTest
	@ArgumentsSource(ListingArgumentProvider.class)
	void testScope(Listing<TestEntity> listing, List<TestEntity> children, Bosk<TestEntity> bosk) throws InvalidTypeException {
		Reference<Catalog<TestEntity>> expected = bosk.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.children));
		assertEquals(expected, listing.domain());
	}

	private List<TestEntity> distinctEntities(List<TestEntity> children) {
		List<TestEntity> result = new ArrayList<>(children.size());
		HashSet<Identifier> added = new HashSet<>(children.size());
		for (TestEntity child: children) {
			if (added.add(child.id())) {
				result.add(child);
			}
		}
		return result;
	}

	private Set<Identifier> distinctEntityIDs(List<TestEntity> children) {
		return children.stream().map(TestEntity::id).collect(toCollection(LinkedHashSet::new));
	}

	private List<Identifier> distinctIDs(List<Identifier> ids) {
		return ids.stream().distinct().collect(toList());
	}

}
