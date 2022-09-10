package io.vena.bosk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.stream.Stream;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.NonFinal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {
	static final BasicEntity a = new BasicEntity(Identifier.unique("m"));
	static final BasicEntity b = new BasicEntity(Identifier.from("\n"));
	static final BasicEntity bNot = new BasicEntity(Identifier.from("\n"));
	static final BasicEntity c = new BasicEntity(Identifier.unique(";͉̙̖̳͙ ̧̺̰͕̭̲ͅd̢͈̣̦ró̜͙̬̬͚̺͔p̡̟ ̠ị̯͕n̮̦̞͝ṱ̩̥e҉͖̻r̜͕̠̝̙͢n͈ ͖̩̹̫̜̪́s͘h҉̺a̲h̹͈̞̜̯̹i̻͕̱̣̯̘̳͝n̞͚͚̟̬̣-̷̭̤̗̼-̘̼̣͎̗͙̗"));
	static final ComplexEntity x = new ComplexEntity(Identifier.unique("1"), "");
	static final BasicEntity xNot = new BasicEntity(Identifier.unique("1"));
	static final ComplexEntity y = new ComplexEntity(Identifier.unique("\n"), "bla");
	static final Identifier mId = Identifier.unique("m");
	static final ComplexEntity z = new ComplexEntity(mId, "goodValue");
	static final ComplexEntity zNot = new ComplexEntity(mId, "badValue");
	static final BasicEntity wrongEntity = new BasicEntity(Identifier.from("wrongEntity"));

	public static Stream<Arguments> distinctCases() {
		return Stream.of(
			entities(new ComplexEntity(Identifier.unique("1"), "")),
			entities(new ComplexEntity(Identifier.unique("1"), "not empty str")),
			entities(new BasicEntity(Identifier.unique("1"))),
			entities(new BasicEntity(Identifier.unique("\n"))),
			entities(new BasicEntity(Identifier.unique(";"))),
			entities());
	}

	public static Stream<Arguments> dupCases() {
		return Stream.of(
			entities(z, zNot, a, b, bNot, c, y,  x, xNot));
	}

	public static Stream<Arguments> allCases() {
		return Stream.concat(distinctCases(), dupCases());
	}

	private static Arguments entities(BasicEntity... members) {
		return Arguments.of((Object)members);
	}

	Catalog<BasicEntity> fromContents(BasicEntity[] contents) {
		Catalog<BasicEntity> result = Catalog.of();
		for (BasicEntity e : contents) {
			result = result.with(e); //creates catalog one element at a time bypassing IllegalArgumentException for duplicate values
		}
		return result;
	}

	LinkedHashSet<BasicEntity> linkedHashSetFromContents(BasicEntity[] contents) {
		LinkedHashSet<BasicEntity> result = new LinkedHashSet<>();
		for(BasicEntity e : contents) {
			result.removeIf(x -> x.id.equals(e.id));
			result.add(e);
		}
		return result;
	}

	@ParameterizedTest
	@MethodSource("distinctCases")
	void ofDistinct_matchesList(BasicEntity[] contents) {
		List<BasicEntity> contentsList = asList(contents);

		assertEquals(
			contentsList,
			Catalog.of(contents).stream().collect(toList())
		);
		assertEquals(
			contentsList,
			Catalog.of(contentsList).stream().collect(toList())
		);
		assertEquals(
			contentsList,
			Catalog.of(Stream.of(contents)).stream().collect(toList())
		);
	}

	@ParameterizedTest
	@MethodSource("dupCases")
	void ofDupes_throws(BasicEntity[] contents) {
		List<BasicEntity> contentsList = asList(contents);

		assertThrows(IllegalArgumentException.class, () -> Catalog.of(contents));
		assertThrows(IllegalArgumentException.class, () -> Catalog.of(contentsList));
		assertThrows(IllegalArgumentException.class, () -> Catalog.of(Stream.of(contents)));
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void size_matchesLinkedHashSet(BasicEntity[] contents) {
		LinkedHashSet<BasicEntity> linkedHashSet = linkedHashSetFromContents(contents);
		assertEquals(linkedHashSet.size(), fromContents(contents).size());
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void isEmpty_matchesLinkedHashSet(BasicEntity[] contents) {
		LinkedHashSet<BasicEntity> linkedHashSet = linkedHashSetFromContents(contents);
		assertEquals(linkedHashSet.isEmpty(), fromContents(contents).isEmpty());
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void ids_matchesLinkedHashSet(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		List<Identifier> IDs = catalog.ids();
		List<Identifier> expected = linkedHashSetFromContents(contents).stream().map(Entity::id).collect(toList());
		assertEquals(expected, IDs);
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void ids_notModifiable(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		assertThrows(UnsupportedOperationException.class, () ->
			catalog.ids().add(Identifier.unique("badID"))
		);
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void idStream_matchesLinkedHashSet(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		LinkedHashSet<BasicEntity> linkedHashSet = new LinkedHashSet<>();

		for(BasicEntity e : contents) {
			if(linkedHashSet.stream().noneMatch(ee -> ee.id == e.id)) {
				linkedHashSet.add(e);
			}
		}

		assertEquals(
			linkedHashSet.stream().map(Entity::id).collect(toList()),
			catalog.idStream().collect(toList())
		);
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void get_matchesLinkedHashSet(BasicEntity[] contents) {
		LinkedHashSet<BasicEntity> linkedHashSet = linkedHashSetFromContents(contents);
		Catalog<BasicEntity> actual = fromContents(contents);

		for (BasicEntity x : linkedHashSet) {
			assertEquals(x, actual.get(x.id));
		}

		assertNull(actual.get(Identifier.unique("nonexistent")));
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void iterator_matchesLinkedHashSet(BasicEntity[] contents) {
		assertThrows(NoSuchElementException.class, () -> fromContents(new BasicEntity[0]).iterator().next());
		Iterator<BasicEntity> expected = linkedHashSetFromContents(contents).iterator();
		Iterator<BasicEntity> actual = fromContents(contents).iterator();

		while (expected.hasNext()) { //assert equality of iterators
			assertTrue(actual.hasNext());
			assertEquals(expected.next(), actual.next());
		}
		assertFalse(actual.hasNext());
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void spliterator_matchesLinkedHashSet(BasicEntity[] contents) {
		Iterator<BasicEntity> expected = linkedHashSetFromContents(contents).iterator();
		Spliterator<BasicEntity> actual = fromContents(contents).spliterator();
		actual.forEachRemaining(e -> assertSame(expected.next(), e));
		assertFalse(expected.hasNext());
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void with(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		BasicEntity entityA = new BasicEntity(Identifier.from("a"));
		BasicEntity entityB = new BasicEntity(Identifier.from("b"));
		Catalog<BasicEntity> withA = catalog.with(entityA);
		assertEquals(catalog, fromContents(contents));
		assertFalse(catalog.contains(entityA));
		assertTrue(withA.contains(entityA));
		assertEquals(catalog.size() + 1, withA.size());

		Catalog<BasicEntity> withAB = withA.with(entityB);
		assertFalse(withA.contains(entityB));
		assertTrue(withAB.contains(entityA));
		assertTrue(withAB.contains(entityB));
		assertEquals(catalog.size() + 2, withAB.size());
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void withAll(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);

		BasicEntity entityA = new BasicEntity(Identifier.from("a"));
		BasicEntity entityB = new BasicEntity(Identifier.from("b"));

		Catalog<BasicEntity> withAB = catalog.withAll(Stream.of(entityA, entityA, entityB));
		assertEquals(linkedHashSetFromContents(contents).size() + 2, withAB.size());
		assertEquals(linkedHashSetFromContents(contents).size(), catalog.size());

		List<BasicEntity> expected = new ArrayList<>(linkedHashSetFromContents(contents));
		expected.add(entityA);
		expected.add(entityB);
		assertEquals(expected, withAB.stream().collect(toList()));
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void asCollection_matchesLinkedHashSet(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		Collection<BasicEntity> actual = catalog.asCollection();
		Collection<BasicEntity> expected = linkedHashSetFromContents(contents);
		assertEquals(new ArrayList<>(expected), new ArrayList<>(actual));
	}

	@ParameterizedTest
	@MethodSource("allCases")
	void asMap_preservesOrder(BasicEntity[] contents) {
		Catalog<BasicEntity> catalog = fromContents(contents);
		Map<Identifier, BasicEntity> actual = catalog.asMap();
		Map<Identifier, BasicEntity> expected = new LinkedHashMap<>();
		for (BasicEntity x: contents) {
			// With dup IDs, use "put" so later dupes win
			expected.put(x.id, x);
		}
		assertEquals(expected, actual);

		// Now force things into lists to check the order

		List<Identifier> actualKeys = new ArrayList<>(actual.keySet());
		List<Identifier> expectedKeys = Stream.of(contents).map(Entity::id).distinct().collect(toList());
		assertEquals(expectedKeys, actualKeys);

		List<BasicEntity> actualValues = new ArrayList<>(actual.values());
		List<BasicEntity> expectedValues = new ArrayList<>(expected.values());
		assertEquals(expectedValues, actualValues);
	}

	@Test
	void without_preservesOrder() {
		BasicEntity[] contents = new BasicEntity[]{
			new BasicEntity(Identifier.from("a")),
			new BasicEntity(Identifier.from("b")),
			new BasicEntity(Identifier.from("c")),
		};

		Catalog<BasicEntity> catalog = fromContents(contents);
		BasicEntity entity = contents[1];
		Catalog<BasicEntity> withoutM = catalog.without(entity);
		assertEquals(catalog, fromContents(contents));
		assertTrue(catalog.contains(entity));

		LinkedHashSet<BasicEntity> lhsWithoutM = linkedHashSetFromContents(contents);
		lhsWithoutM.remove(entity);
		assertEquals(new LinkedHashSet<>(withoutM.asCollection()), lhsWithoutM); // since lhs is ordered, our catalog must be ordered too

		Catalog<BasicEntity> withoutMById = catalog.without(entity.id);
		assertEquals(catalog, fromContents(contents));
		assertEquals(withoutM, withoutMById);
	}

	@Test
	void asCollection_mutationThrows() {
		assertThrows(UnsupportedOperationException.class, () -> Catalog.empty().asCollection().add(wrongEntity));
		assertThrows(UnsupportedOperationException.class, () -> Catalog.empty().asCollection().remove(wrongEntity));
		assertThrows(UnsupportedOperationException.class, () -> Catalog.empty().asCollection().addAll(asList(a, b)));
		assertThrows(UnsupportedOperationException.class, () -> Catalog.empty().asCollection().removeAll(singletonList(wrongEntity)));
		assertThrows(UnsupportedOperationException.class, () -> Catalog.empty().asCollection().retainAll(singletonList(wrongEntity)));
	}

	@Value
	@NonFinal
	@Accessors(fluent = true)
	private static class BasicEntity implements Entity {
		Identifier id;
	}

	@Value
	@Accessors(fluent = true)
	@EqualsAndHashCode(callSuper = true)
	private static class ComplexEntity extends BasicEntity {
		@EqualsAndHashCode.Include
		String value;

		ComplexEntity(Identifier id, String value) {
			super(id);
			this.value = value;
		}
	}

}
