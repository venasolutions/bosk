package io.vena.bosk;

import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.AbstractMap.SimpleEntry;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideTableTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	Refs refs;
	Bosk<TestRoot>.ReadContext readContext;
	TestEntity firstEntity;

	public interface Refs {
		@ReferencePath("/entities") CatalogReference<TestEntity> entities();
		@ReferencePath("/entities/parent/children") CatalogReference<TestChild> children();
		@ReferencePath("/entities/parent/stringSideTable") SideTableReference<TestChild, String> sideTable();
	}

	@BeforeEach
	void setup() throws InvalidTypeException {
		bosk = setUpBosk(Bosk::simpleDriver);
		refs = bosk.buildReferences(Refs.class);

		readContext = bosk.readContext();
		firstEntity = refs.entities().value().iterator().next();
	}

	@AfterEach
	void teardown() {
		readContext.close();
	}

	@Test
	void empty_matchesEmptyMap() {
		assertEqualsOrderedMap(emptyMap(), SideTable.empty(refs.entities()));
	}

	@Test
	void singleton_matchesSingletonMap() {
		Identifier id = Identifier.from("x");
		assertEqualsOrderedMap(singletonMap(id, "value"), SideTable.of(refs.entities(), id, "value"));
		assertEqualsOrderedMap(singletonMap(firstEntity.id(), "value"), SideTable.of(refs.entities(), firstEntity, "value"));
	}

	@Test
	void has() {
		SideTable<TestEntity, String> table = SideTable.of(refs.entities(), firstEntity, "value");
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

		assertEqualsOrderedMap(expected, SideTable.fromOrderedMap(refs.entities(), expected));
		assertEqualsOrderedMap(expected, SideTable.fromFunction(refs.entities(), expected.keySet().stream(), expected::get));
		assertEqualsOrderedMap(expected, SideTable.fromEntries(refs.entities(), expected.entrySet().stream()));
	}

	@Test
	void duplicateEntries_throws() {
		assertThrows(IllegalArgumentException.class, ()-> {
			SideTable.fromFunction(refs.entities(), Stream.of("dup", "dup").map(Identifier::from), Identifier::toString);
		});
		assertThrows(IllegalArgumentException.class, ()-> {
			SideTable.fromEntries(refs.entities(), Stream.of("dup", "dup").map(v -> new SimpleEntry<>(Identifier.from(v), v)));
		});
	}

	@Test
	void forEachValue_expectedResults() {
		Map<TestChild, String> expected = new LinkedHashMap<>();
		Map<TestChild, String> actual = new LinkedHashMap<>();
		try (var __ = bosk.readContext()) {
			SideTable<TestChild, String> sideTable = refs.sideTable().value();

			// Record everything that forEachValue produces
			sideTable.forEachValue(actual::put);

			// Compute what it ought to have produced
			Catalog<TestChild> entities = refs.children().value();
			expected.put(entities.get(Identifier.from("child2")), "I'm child 2");
		}

		assertEquals(expected, actual);
	}

	private <V> void assertEqualsOrderedMap(Map<Identifier,V> expected, SideTable<TestEntity,V> actual) {
		assertEquals(expected, actual.asMap());

		assertEquals(expected.isEmpty(), actual.isEmpty());
		assertEquals(expected.size(), actual.size());
		assertEquals(new ArrayList<>(expected.keySet()), actual.ids());
		assertEquals(Listing.of(refs.entities(), actual.ids()), actual.keys());
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
		assertEquals(refs.entities(), actual.domain());
	}
}
