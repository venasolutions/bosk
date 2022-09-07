package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.PerformanceTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.MicroBenchmark.callingMethodInfo;
import static io.vena.bosk.util.Classes.catalog;
import static io.vena.bosk.util.Classes.listing;
import static io.vena.bosk.util.Classes.mapValue;
import static io.vena.bosk.util.Classes.reference;
import static io.vena.bosk.util.Classes.sideTable;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

		assertEquals(parent.string(), parentRef.then(String.class, TestEntity.Fields.string).value());
		assertSame(parent.testEnum(), parentRef.then(TestEnum.class, TestEntity.Fields.testEnum).value());
		assertSame(parent.children(), parentRef.thenCatalog(TestChild.class, TestEntity.Fields.children).value());
		assertSame(parent.oddChildren(), parentRef.thenListing(TestChild.class, TestEntity.Fields.oddChildren).value());
		assertSame(parent.stringSideTable(), parentRef.thenSideTable(TestChild.class, String.class, TestEntity.Fields.stringSideTable).value());
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
		assertNull(phantomsRef.then(sideTable(TestChild.class, String.class), Phantoms.Fields.phantomSideTable).valueIfExists());
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
		assertSame(optionals.optionalSideTable().orElse(null), optionalsRef.then(sideTable(TestChild.class, String.class), Optionals.Fields.optionalSideTable).valueIfExists());
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
				TestRoot.Fields.entities, "nonexistent", TestEntity.Fields.stringSideTable, "-child-")),
			emptyList(),
			emptyList()
		);
	}

	@Test
	void forEach_indefiniteReference_oneMatch() throws InvalidTypeException {
		assertForEachValueWorks(
			bosk.reference(String.class, Path.of(
				TestRoot.Fields.entities, "parent", TestEntity.Fields.stringSideTable, "-child-")),
			singletonList(root.entities().get(Identifier.from("parent")).stringSideTable().get(Identifier.from("child2"))),
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

	@PerformanceTest
	void referencePerf_emptyBenchmark() {
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_reused_root() {
		Reference<TestRoot> rootRef = bosk.rootReference();
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					rootRef.value();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_fresh_root() {
		double rate = new MicroBenchmark(callingMethodInfo()) {
			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					bosk.rootReference().value();
				}
			}
		}.computeRate();
}

	@PerformanceTest
	void referencePerf_reused_5segments() throws InvalidTypeException {
		Reference<TestEnum> ref = bosk.reference(TestEnum.class, Path.of(
			TestRoot.Fields.entities, "parent",
			TestEntity.Fields.children, "child1",
			TestChild.Fields.testEnum
		));
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = ref.value();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_javaOnly_5segments() {
		Identifier parentID = Identifier.from("parent");
		Identifier child1ID = Identifier.from("child1");
		ThreadLocal<TestRoot> root = ThreadLocal.withInitial(bosk.rootReference()::value);
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = root.get().entities().get(parentID).children().get(child1ID).testEnum();
				}
			}
		}.computeRate();
	}

	@PerformanceTest
	void referencePerf_javaObjectsOnly_5segments() {
		Identifier parentID = Identifier.from("parent");
		Identifier child1ID = Identifier.from("child1");
		TestRoot root = bosk.rootReference().value();
		double rate = new MicroBenchmark(callingMethodInfo()) {
			public TestEnum escape;

			@Override
			protected void doIterations(long count) {
				for (long i = 0; i < count; i++) {
					escape = root.entities().get(parentID).children().get(child1ID).testEnum();
				}
			}
		}.computeRate();
	}
}
