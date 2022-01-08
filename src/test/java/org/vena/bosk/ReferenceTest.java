package org.vena.bosk;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.vena.bosk.util.Classes.catalog;
import static org.vena.bosk.util.Classes.listing;
import static org.vena.bosk.util.Classes.mapValue;
import static org.vena.bosk.util.Classes.mapping;
import static org.vena.bosk.util.Classes.reference;

class ReferenceTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestRoot root;
	private Bosk<TestRoot>.ReadContext context;

	@BeforeEach
	void setup() {
		this.bosk = setUpBosk(Bosk::simpleDriver);
		context = bosk.readContext();
		this.root = bosk.rootReference().value();
	}

	@AfterEach
	void closeReadContext() {
		context.close();
	}

	@Test
	void rootFields_referenceValue_returnsCorrectObject() throws InvalidTypeException {
		assertSame(root.entities(), bosk.catalogReference(TestEntity.class, Path.just(
			TestRoot.Fields.entities
		)).value());
		assertSame(root.someStrings(), bosk.reference(StringListValueSubclass.class, Path.just(
			TestRoot.Fields.someStrings
		)).value());
		assertSame(root.someMappedStrings(), bosk.reference(mapValue(String.class), Path.just(
			TestRoot.Fields.someMappedStrings
		)).value());
	}

	@Test
	void parentFields_referenceValue_returnsCorrectObject() throws InvalidTypeException {
		Reference<TestEntity> parentRef = bosk.reference(TestEntity.class, Path.of(
			TestRoot.Fields.entities, "parent"
		));
		TestEntity parent = root.entities().get(Identifier.from("parent"));
		assertSame(parent, parentRef.value());

		assertSame(parent.string(), parentRef.then(String.class, TestEntity.Fields.string).value());
		assertSame(parent.testEnum(), parentRef.then(TestEnum.class, TestEntity.Fields.testEnum).value());
		assertSame(parent.children(), parentRef.thenCatalog(TestChild.class, TestEntity.Fields.children).value());
		assertSame(parent.oddChildren(), parentRef.thenListing(TestChild.class, TestEntity.Fields.oddChildren).value());
		assertSame(parent.stringMapping(), parentRef.thenMapping(TestChild.class, String.class, TestEntity.Fields.stringMapping).value());
		assertSame(parent.phantoms(), parentRef.then(Phantoms.class, TestEntity.Fields.phantoms).value());
		assertSame(parent.optionals(), parentRef.then(Optionals.class, TestEntity.Fields.optionals).value());
		assertSame(parent.implicitRefs(), parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs).value());
	}

	@Test
	void phantomFields_reference_nonexistent() throws InvalidTypeException {
		Reference<Phantoms> phantomsRef = bosk.reference(Phantoms.class, Path.of(
			TestRoot.Fields.entities, "parent", TestEntity.Fields.phantoms
		));
		Phantoms phantoms = root.entities().get(Identifier.from("parent")).phantoms();
		assertSame(phantoms, phantomsRef.value());

		assertNull(phantomsRef.then(String.class, Phantoms.Fields.phantomString).valueIfExists());
		assertNull(phantomsRef.then(TestChild.class, Phantoms.Fields.phantomEntity).valueIfExists());
		assertNull(phantomsRef.then(reference(TestEntity.class), Phantoms.Fields.phantomRef).valueIfExists());
		assertNull(phantomsRef.then(catalog(TestChild.class), Phantoms.Fields.phantomCatalog).valueIfExists());
		assertNull(phantomsRef.then(listing(TestChild.class), Phantoms.Fields.phantomListing).valueIfExists());
		assertNull(phantomsRef.then(mapping(TestChild.class, String.class), Phantoms.Fields.phantomMapping).valueIfExists());
	}

	@Test
	void optionalFields_referenceValueIfExists_returnsCorrectResult() throws InvalidTypeException {
		Reference<Optionals> optionalsRef = bosk.reference(Optionals.class, Path.of(
			TestRoot.Fields.entities, "parent", TestEntity.Fields.optionals
		));
		Optionals optionals = root.entities().get(Identifier.from("parent")).optionals();
		assertSame(optionals, optionalsRef.value());

		assertSame(optionals.optionalString().orElse(null), optionalsRef.then(String.class, Optionals.Fields.optionalString).valueIfExists());
		assertSame(optionals.optionalEntity().orElse(null), optionalsRef.then(TestChild.class, Optionals.Fields.optionalEntity).valueIfExists());
		assertSame(optionals.optionalRef().orElse(null), optionalsRef.then(reference(TestEntity.class), Optionals.Fields.optionalRef).valueIfExists());
		assertSame(optionals.optionalCatalog().orElse(null), optionalsRef.then(catalog(TestChild.class), Optionals.Fields.optionalCatalog).valueIfExists());
		assertSame(optionals.optionalListing().orElse(null), optionalsRef.then(listing(TestChild.class), Optionals.Fields.optionalListing).valueIfExists());
		assertSame(optionals.optionalMapping().orElse(null), optionalsRef.then(mapping(TestChild.class, String.class), Optionals.Fields.optionalMapping).valueIfExists());
	}

	@Test
	void forEach_definiteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "nonexistent")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_definiteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(TestEntity.class, Path.of(
				TestRoot.Fields.entities, "parent")),
			singletonList(root.entities().get(Identifier.from("parent"))),
			singletonList(BindingEnvironment.empty())
		);
	}

	@Test
	void forEach_indefiniteReference_noMatches() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(String.class, Path.of(
				TestRoot.Fields.entities, "nonexistent", TestEntity.Fields.stringMapping, "-child-")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_indefiniteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(String.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.stringMapping, "-child-")),
			singletonList(root.entities().get(Identifier.from("parent")).stringMapping().get(Identifier.from("child2"))),
			singletonList(BindingEnvironment.singleton("child", Identifier.from("child2")))
		);
	}

	@Test
	void forEach_indefiniteReference_multipleMatches() throws InvalidTypeException {
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
		ref.forEachValue((T v, BindingEnvironment e) -> {
			actualValues.add(v);
			actualEnvironments.add(e);
		}, BindingEnvironment.empty()); // TODO: test nontrivial initial environment

		assertEquals(expectedValues, actualValues);
		assertEquals(expectedEnvironments, actualEnvironments);
	}
}
