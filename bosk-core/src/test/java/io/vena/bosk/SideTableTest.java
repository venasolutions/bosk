package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.*;

class SideTableTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	CatalogReference<TestEntity> entitiesRef;
	Bosk<TestRoot>.ReadContext readContext;
	TestEntity firstEntity;

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = setUpBosk(Bosk::simpleDriver);
		entitiesRef = bosk.catalogReference(TestEntity.class, Path.just(TestRoot.Fields.entities));
		readContext = bosk.readContext();
		firstEntity = entitiesRef.value().iterator().next();
	}

	@AfterEach
	void teardown() {
		readContext.close();
	}

	@Test
	void empty_matchesEmptyMap() {
		assertEqualsOrderedMap(emptyMap(), SideTable.empty(entitiesRef));
	}

	@Test
	void singleton_matchesSingletonMap() {
		Identifier id = Identifier.from("x");
		assertEqualsOrderedMap(singletonMap(id, "value"), SideTable.of(entitiesRef, id, "value"));
		assertEqualsOrderedMap(singletonMap(firstEntity.id(), "value"), SideTable.of(entitiesRef, firstEntity, "value"));
	}

	@Test
	void has() {
		SideTable<TestEntity, String> table = SideTable.of(entitiesRef, firstEntity, "value");
		assertTrue(table.hasID(firstEntity.id()));
		assertTrue(table.hasKey(firstEntity));

		Identifier nonexistent = Identifier.from("nonexistent");
		assertFalse(table.hasID(nonexistent));
		assertFalse(table.hasKey(firstEntity.withId(nonexistent)));
	}

	@Test
	void multipleEntries_matchesLinkedHashMap() {
		// Note that these IDs are not in lexicographic order. This tests that we retain insertion order.
		Identifier id1 = Identifier.from("first");
		Identifier id2 = Identifier.from("second");
		Identifier id3 = Identifier.from("third");
		Identifier id4 = Identifier.from("fourth");
		Map<Identifier, String> expected = new LinkedHashMap<>();
		expected.put(id1, "value1");
		expected.put(id2, "value2");
		expected.put(id3, "value3");
		expected.put(id4, "value4");

		assertEqualsOrderedMap(expected, SideTable.fromOrderedMap(entitiesRef, expected));
		assertEqualsOrderedMap(expected, SideTable.fromFunction(entitiesRef, expected.keySet().stream(), expected::get));
	}

	@Test
	void duplicateEntries_throws() {
		assertThrows(IllegalArgumentException.class, ()-> {
			SideTable.fromFunction(entitiesRef, Stream.of("dup", "dup").map(Identifier::from), Identifier::toString);
		});
	}

	private <V> void assertEqualsOrderedMap(Map<Identifier,V> expected, SideTable<TestEntity,V> actual) {
		assertEquals(expected, actual.asMap());

		assertEquals(expected.isEmpty(), actual.isEmpty());
		assertEquals(expected.size(), actual.size());
		assertEquals(new ArrayList<>(expected.keySet()), actual.ids());
		assertEquals(Listing.of(entitiesRef, actual.ids()), actual.keys());
		assertEquals(new ArrayList<>(expected.values()), new ArrayList<>(actual.values()));
		assertEquals(new ArrayList<>(expected.entrySet()), new ArrayList<>(actual.idEntrySet()));

		ArrayList<Identifier> expectedIDs = new ArrayList<>(expected.keySet());
		assertEquals(expectedIDs, actual.ids());
		assertEquals(expectedIDs, actual.keys().idStream().collect(toList()));

		expected.forEach((id,value) -> {
			assertEquals(expected.get(id), actual.get(id));
			TestEntity phonyEntity = firstEntity.withId(id);
			assertEquals(expected.get(id), actual.get(phonyEntity));
			assertTrue(actual.hasID(id));
			assertTrue(actual.hasKey(phonyEntity));
		});

		// No dupes
		Map<Identifier, V> encounteredItems = new LinkedHashMap<>();
		actual.forEachID((id, v) -> {
			V existing = encounteredItems.put(id,v);
			assertNull(existing);
		});
		assertEquals(expected, encounteredItems);

		// Sundry checks
		assertEquals(entitiesRef, actual.domain());
	}
}
