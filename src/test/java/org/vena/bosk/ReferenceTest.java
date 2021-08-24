package org.vena.bosk;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestRoot root;

	@BeforeEach
	void setup() {
		this.bosk = setUpBosk(Bosk::simpleDriver);
		try (Bosk<?>.ReadContext context = bosk.readContext()) {
			this.root = bosk.rootReference().value();
		}
	}

	@Test
	void testForEach_definiteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "nonexistent")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void testForEach_definiteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "parent")),
			singletonList(root.entities().get(Identifier.from("parent"))),
			singletonList(BindingEnvironment.empty())
		);
	}

	@Test
	void testForEach_indefiniteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(String.class, Path.of(
				TestRoot.Fields.entities, "nonexistent", TestEntity.Fields.stringMapping, "-child-")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void testForEach_indefiniteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(String.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.stringMapping, "-child-")),
			singletonList(root.entities().get(Identifier.from("parent")).stringMapping().get(Identifier.from("child2"))),
			singletonList(BindingEnvironment.singleton("child", Identifier.from("child2")))
		);
	}

	@Test
	void testForEach_indefiniteReference_multipleMatches() throws InvalidTypeException {
		Catalog<TestChild> children = root.entities().get(Identifier.from("parent")).children();
		assertForEachValueWorks(
			bosk.reference(TestChild.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.children, "-child-")),
			children.stream().collect(toList()),
			children.idStream().map(id -> BindingEnvironment.singleton("child", id)).collect(toList())
		);
	}

	private <T> void assertForEachValueWorks(Reference<T> ref, List<T> expectedValues, List<BindingEnvironment> expectedEnvironments) {
		List<T> actualValues = new ArrayList<>();
		List<BindingEnvironment> actualEnvironments = new ArrayList<>();
		try (Bosk<?>.ReadContext context = bosk.readContext()) {
			ref.forEachValue((T v, BindingEnvironment e) -> {
				actualValues.add(v);
				actualEnvironments.add(e);
			}, BindingEnvironment.empty()); // TODO: test nontrivial initial environment
		}
		assertEquals(expectedValues, actualValues);
		assertEquals(expectedEnvironments, actualEnvironments);
	}
}
