package org.vena.bosk.dereferencers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.Value;
import lombok.experimental.Accessors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.vena.bosk.AbstractBoskTest;
import org.vena.bosk.Bosk;
import org.vena.bosk.Bosk.NonexistentEntryException;
import org.vena.bosk.Catalog;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.Listing;
import org.vena.bosk.MapValue;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.SideTable;
import org.vena.bosk.TestEntityBuilder;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.NonexistentReferenceException;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.vena.bosk.ListingEntry.LISTING_ENTRY;

public class PathCompilerTest extends AbstractBoskTest {
	PathCompiler pathCompiler;
	Bosk<TestRoot> bosk;
	TestRoot root;
	TestEntityBuilder teb;

	private final Identifier parentID = Identifier.from("parent");
	private final Identifier child1ID = Identifier.from("child1");

	@BeforeEach
	void setup() throws InvalidTypeException, InterruptedException, IOException {
		pathCompiler = PathCompiler.withSourceType(TestRoot.class);
		bosk = setUpBosk(Bosk::simpleDriver);
		teb = new TestEntityBuilder(bosk);
		root = initialRoot(bosk);
		bosk.driver().submitReplacement(bosk.rootReference(), root);
		bosk.driver().flush();
	}

	@TestFactory
	Stream<DynamicTest> compiler_shouldThrow_withBadPath() {
		return Stream.of(
			assertThrowsInvalidTypeException(Path.parse("/no/such/thing")),
			assertThrowsInvalidTypeException(Path.just("-p-")),
			assertThrowsInvalidTypeException(Path.of(TestRoot.Fields.entities, "parent", "-p-"))
		).flatMap(x->x);
	}

	private Stream<DynamicTest> assertThrowsInvalidTypeException(Path path) {
		return Stream.of(
			dynamicTest("Should throw: compiled(" + path + ")", ()->assertThrows(InvalidTypeException.class, () -> pathCompiler.compiled(path))),
			dynamicTest("Should throw: targetTypeOf(" + path + ")", ()->assertThrows(InvalidTypeException.class, () -> pathCompiler.targetTypeOf(path)))
		);
	}

	/**
	 * Ensure we're not allowed to poke around outside the walled garden.
	 */
	@Test
	void noseyPath_throws() {
		assertThrows(InvalidTypeException.class, () -> pathCompiler.compiled(Path.parse("/entities/parent/string/length")));
	}

	@TestFactory
	List<DynamicTest> root() {
		Dereferencer expected = dereferencer(
			s->s,
			(s,v)->v,
			(s)->{ throw new IllegalArgumentException("Can't delete root"); }
		);
		return standardEquivalenceTests(expected, "/", new TestRoot(Identifier.from("newRoot"), Catalog.empty(), new StringListValueSubclass("A string"), MapValue.singleton("key", "value")));
	}

	@TestFactory
	List<DynamicTest> catalog() {
		Dereferencer expected = fieldDereferencer(
			TestRoot::entities,
			TestRoot::withEntities
		);
		return standardEquivalenceTests(expected, "/entities", Catalog.empty());
	}

	@TestFactory
	List<DynamicTest> catalogEntry() {
		Dereferencer expected = dereferencer(
			s->s.entities().get(parentID),
			(s,v) -> s.withEntities(s.entities().with(v)),
			s->s.withEntities(s.entities().without(parentID))
		);
		return standardEquivalenceTests(expected, "/entities/parent", teb.blankEntity(parentID, TestEnum.OK));
	}

	@TestFactory
	List<DynamicTest> nestedCatalog() {
		Dereferencer expected = fieldDereferencer(
			s->s.entities().get(parentID).children(),
			(s,v) -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withChildren(v)))
		);
		return standardEquivalenceTests(expected, "/entities/parent/children", Catalog.empty());
	}

	@TestFactory
	List<DynamicTest> nestedCatalogEntry() {
		// Wow, deep dereferencers get to be a pain to build by hand...
		Dereferencer expected = dereferencer(
			s->s.entities().get(parentID).children().get(child1ID),
			(s,v) -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withChildren(s.entities().get(parentID).children().with(v)))),
			s-> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withChildren(s.entities().get(parentID).children().without(child1ID))))
		);
		return standardEquivalenceTests(expected, "/entities/parent/children/child1", new TestChild(child1ID, "New child 1", TestEnum.OK, Catalog.empty()));
	}

	@TestFactory
	List<DynamicTest> listing() {
		Dereferencer expected = fieldDereferencer(
			s->s.entities().get(parentID).oddChildren(),
			(s,v) -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withOddChildren(v)))
		);
		return standardEquivalenceTests(expected, "/entities/parent/oddChildren", Listing.empty(teb.childrenRef(parentID)));
	}

	@TestFactory
	List<DynamicTest> listingEntry() {
		List<DynamicTest> results = new ArrayList<>();
		for (Identifier childID: ids("child1", "child2", "child3", "nonexistent")) {
			Dereferencer expected = dereferencer(
				s->s.entities().get(parentID).oddChildren().containsID(childID) ? LISTING_ENTRY : null,
				(s,v) -> s
					.withEntities(s.entities()
						.with(s.entities().get(parentID)
							.withOddChildren(s.entities().get(parentID).oddChildren()
								.withID(childID)))), // v can only be LISTING_ENTRY
				s -> s
					.withEntities(s.entities()
						.with(s.entities().get(parentID)
							.withOddChildren(s.entities().get(parentID).oddChildren()
								.withoutID(childID))))
			);
			results.addAll(
				standardEquivalenceTests(expected, "/entities/parent/oddChildren/" + childID, LISTING_ENTRY)
			);
		}
		return results;
	}

	@TestFactory
	List<DynamicTest> sideTable() {
		Dereferencer expected = fieldDereferencer(
			s->s.entities().get(parentID).stringSideTable(),
			(s,v) -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withStringSideTable(v)))
		);
		return standardEquivalenceTests(expected, "/entities/parent/stringSideTable", SideTable.empty(teb.childrenRef(parentID)));
	}

	@TestFactory
	List<DynamicTest> sideTableEntry() {
		List<DynamicTest> results = new ArrayList<>();
		for (Identifier childID: ids("child1", "child2", "child3", "nonexistent")) {
			Dereferencer expected = dereferencer(
				s->s.entities().get(parentID).stringSideTable().get(childID),
				(s,v) -> s
					.withEntities(s.entities()
						.with(s.entities().get(parentID)
							.withStringSideTable(s.entities().get(parentID).stringSideTable()
								.with(childID, v)))),
				s -> s
					.withEntities(s.entities()
						.with(s.entities().get(parentID)
							.withStringSideTable(s.entities().get(parentID).stringSideTable()
								.without(childID))))
			);
			results.addAll(
				standardEquivalenceTests(expected, "/entities/parent/stringSideTable/" + childID, "Example string")
			);
		}
		return results;
	}

	@TestFactory
	List<DynamicTest> optional() {
		Dereferencer expected = dereferencer(
			s->s.entities().get(parentID).optionals().optionalString().orElse(null),
			(s,v) -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withOptionals(s.entities().get(parentID).optionals()
							.withOptionalString(Optional.of(v))))),
			s -> s
				.withEntities(s.entities()
					.with(s.entities().get(parentID)
						.withOptionals(s.entities().get(parentID).optionals()
							.withOptionalString(Optional.empty()))))
		);
		return standardEquivalenceTests(expected, "/entities/parent/optionals/optionalString", "Example string");
	}

	@Test
	void differentClassLoader() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException, InvalidTypeException {
		ClassLoader parent = getClass().getClassLoader();
		ClassLoader classLoader = new ClassLoader(parent) {
			volatile Class<?> memo = null;

			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
				if (SimpleEntity.class.getName().equals(name)) {
					synchronized (this) {
						if (memo == null) {
							byte[] bytes = classBytes(SimpleEntity.class);
							Class<?> c = defineClass(name, bytes, 0, bytes.length);
							if (resolve) {
								resolveClass(c);
							}
							memo = c;
						}
					}
					return memo;
				} else {
					return super.loadClass(name, resolve);
				}
			}
		};

		Class<? extends Entity> rootClass = SimpleEntity.class;
		@SuppressWarnings({"unchecked","rawtypes"})
		Class<? extends Entity> differentRootClass = (Class)classLoader.loadClass(SimpleEntity.class.getName());
		assertNotSame(rootClass, differentRootClass);

		Identifier rootID = Identifier.from("root");
		Entity initialRoot = differentRootClass
			.getConstructor(Identifier.class)
			.newInstance(rootID);
		Bosk<Entity> differentBosk = new Bosk<>(
			"Different",
			differentRootClass,
			initialRoot,
			Bosk::simpleDriver
		);
		Reference<Identifier> idRef = differentBosk.reference(Identifier.class, Path.parse(
			"/id" ));

		try (Bosk<Entity>.ReadContext context = differentBosk.readContext()) {
			assertSame(rootID, idRef.valueIfExists());
		}
	}

	@Test
	void parameterize_similarPaths_sameDereferencer() throws InvalidTypeException {
		Path p1 = Path.of(TestRoot.Fields.entities, "e1", TestEntity.Fields.children, "c1", TestChild.Fields.string);
		Path p2 = Path.of(TestRoot.Fields.entities, "e2", TestEntity.Fields.children, "c2", TestChild.Fields.string);
		Dereferencer d1 = pathCompiler.compiled(p1);
		Dereferencer d2 = pathCompiler.compiled(p2);
		assertSame(d1, d2);
	}

	@Test
	void parameterize_catalogEntry_entryType() throws InvalidTypeException {
		Path p1 = Path.of(TestRoot.Fields.entities, "e1");
		Path expected = Path.of(TestRoot.Fields.entities, "-testEntity-");
		assertEquals(expected, pathCompiler.fullyParameterizedPathOf(p1));
	}

	@Test
	void parameterize_listingEntry_entryType() throws InvalidTypeException {
		Path p1 = Path.of(
			TestRoot.Fields.entities, "e1",
			TestEntity.Fields.oddChildren, "c1");
		Path expected = Path.of(
			TestRoot.Fields.entities, "-testEntity-",
			TestEntity.Fields.oddChildren, "-testChild-");
		assertEquals(expected, pathCompiler.fullyParameterizedPathOf(p1));
	}

	@Test
	void parameterize_sideTableEntry_keyType() throws InvalidTypeException {
		Path p1 = Path.of(
			TestRoot.Fields.entities, "e1",
			TestEntity.Fields.stringSideTable, "c1");
		Path expected = Path.of(
			TestRoot.Fields.entities, "-testEntity-",
			TestEntity.Fields.stringSideTable, "-testChild-");
		assertEquals(expected, pathCompiler.fullyParameterizedPathOf(p1));
	}

	@Test
	void parameterize_optional_fieldName() throws InvalidTypeException {
		Path p1 = Path.of(
			TestRoot.Fields.entities, "e1",
			TestEntity.Fields.optionals, Optionals.Fields.optionalString);
		Path expected = Path.of(
			TestRoot.Fields.entities, "-testEntity-",
			TestEntity.Fields.optionals, Optionals.Fields.optionalString);
		assertEquals(expected, pathCompiler.fullyParameterizedPathOf(p1));
	}

	@Test
	void parameterize_phantom_fieldName() throws InvalidTypeException {
		Path p1 = Path.of(
			TestRoot.Fields.entities, "e1",
			TestEntity.Fields.phantoms, Phantoms.Fields.phantomString);
		Path expected = Path.of(
			TestRoot.Fields.entities, "-testEntity-",
			TestEntity.Fields.phantoms, Phantoms.Fields.phantomString);
		assertEquals(expected, pathCompiler.fullyParameterizedPathOf(p1));
	}

	@Test
	void parameterize_recursivePath_disambiguated() throws InvalidTypeException {
		Path p1 = Path.of(
			TestRoot.Fields.entities, "e1",
			TestEntity.Fields.children, "c1",
			TestChild.Fields.recursiveChildren, "c2");
		Path parameterized = Path.of(
			TestRoot.Fields.entities, "-testEntity-",
			TestEntity.Fields.children, "-testChild-",
			TestChild.Fields.recursiveChildren, "-testChild_2-");
		assertEquals(parameterized, pathCompiler.fullyParameterizedPathOf(p1));
		pathCompiler.compiled(p1); // just make sure this doesn't throw
	}

	@Value
	@Accessors(fluent = true)
	public static class SimpleEntity implements Entity {
		Identifier id;
	}

	private byte[] classBytes(Class<?> c) {
		InputStream inputStream = c.getClassLoader().getResourceAsStream(
			c.getName().replace('.', '/') + ".class");
		try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream()) {
			int codePoint;
			while ( (codePoint = inputStream.read()) != -1 ) {
				byteStream.write(codePoint);
			}
			return byteStream.toByteArray();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}


	private List<DynamicTest> standardEquivalenceTests(Dereferencer expected, String pathString, Object exampleValue) {
		String description = '[' + pathString + ']';
		Path path = Path.parse(pathString);

		Reference<?> ref;
		Dereferencer actual;
		try {
			// This code uses PathCompiler. A bug there can cause these to throw.
			// Because this is a test of PathCompiler, we want this to look like
			// a test failure, not an initialization error, especially because
			// pitest doesn't count initialization errors as "killed mutations".
			ref = bosk.reference(Object.class, path);
			actual = pathCompiler.compiled(path);
		} catch (Exception | AssertionError e) {
			return singletonList(DynamicTest.dynamicTest(description + ": PathCompiler should not throw", () -> { throw new AssertionError("PathCompiler exception", e); }));
		}

		// The pattern here is:
		// 1. Determine the expected behaviour before the test begins, outside any lambdas
		// 2. If the expected behaviour is a valid exception that a Dereferencer may throw, create a test that asserts the same exception
		// 3. else, create a test that asserts the correct return value
		// 4. Bonus: also assert the correct result from the corresponding Reference method
		List<DynamicTest> tests = new ArrayList<>();

		try {
			Object expectedGet = expected.get(root, ref);
			tests.add(dynamicTest(description + ": Dereferencer.get should return the right object", () ->
				assertSame(expectedGet, actual.get(root, ref))));
			try (Bosk<TestRoot>.ReadContext context = bosk.readContext()) {
				tests.add(dynamicTest(description + ": Reference.value should return the right object", () ->
					usingContext(context, () -> assertSame(expectedGet, ref.value()))));
				tests.add(dynamicTest(description + ": Reference.valueIfExists should return the right object", () ->
					usingContext(context, () -> assertSame(expectedGet, ref.valueIfExists()))));
				tests.add(dynamicTest(description + ": Reference.optionalValue should return the right object", () ->
					usingContext(context, () -> assertSame(expectedGet, ref.optionalValue().orElse(null)))));
			}
		} catch (NonexistentEntryException e) {
			tests.add(dynamicTest(description + ": Dereferencer.get should throw " + e.getClass().getSimpleName(), () ->
				assertThrows(e.getClass(), () -> actual.get(root, ref))));
			try (Bosk<TestRoot>.ReadContext context = bosk.readContext()) {
				tests.add(dynamicTest(description + ": Reference.value should throw " + e.getClass().getSimpleName(), () ->
					usingContext(context, () -> assertThrows(NonexistentReferenceException.class, ref::value))));
				tests.add(dynamicTest(description + ": Reference.valueIfExists should return null", () ->
					usingContext(context, () -> assertNull(ref.valueIfExists()))));
				tests.add(dynamicTest(description + ": Reference.optionalValue should return empty()", () ->
					usingContext(context, () -> assertFalse(ref.optionalValue().isPresent()))));
			}
		}

		try {
			Object expectedWith = expected.with(root, ref, exampleValue);
			tests.add(dynamicTest(description + ": Dereferencer.with should return the expected result", () ->
			{
				Object actualWith = actual.with(root, ref, exampleValue);
				assertEquals(expectedWith, actualWith);
				assertSame(exampleValue, actual.get(actualWith, ref), description + ": Dereferencer.get after with should return the new value");
			}));
		} catch (NonexistentEntryException | IllegalArgumentException e) {
			tests.add(dynamicTest(description + ": With should throw " + e.getClass().getSimpleName(), () ->
				assertThrows(e.getClass(), () -> actual.with(root, ref, exampleValue))));
		}

		try {
			Object expectedWithout = expected.without(root, ref);
			tests.add(dynamicTest(description + ": Dereferencer.without should return the expected result", () ->
			{
				Object actualWithout = actual.without(root, ref);
				assertEquals(expectedWithout, actualWithout);
				assertThrows(NonexistentEntryException.class, () -> actual.get(actualWithout, ref), description + ": Dereferencer.get after without should throw NonexistentEntryException");
			}));
		} catch (NonexistentEntryException | IllegalArgumentException e) {
			tests.add(dynamicTest(description + ": Dereferencer.without should throw " + e.getClass().getSimpleName(), () ->
				assertThrows(e.getClass(), () -> actual.without(root, ref))));
		}

		return tests;
	}

	private void usingContext(Bosk<TestRoot>.ReadContext context, Runnable action) {
		try (@SuppressWarnings("unused") Bosk<TestRoot>.ReadContext rc = context.adopt()) {
			action.run();
		}
	}

	@SuppressWarnings("unchecked")
	private <V> Dereferencer dereferencer(Function<TestRoot, V> get, BiFunction<TestRoot, V, TestRoot> with, Function<TestRoot, TestRoot> without) {
		return new Dereferencer() {
			@Override public Object get(Object source, Reference<?> ref) throws NonexistentEntryException { return throwIfNull(get.apply((TestRoot) source)); }
			@Override public Object with(Object source, Reference<?> ref, Object newValue) { return with.apply((TestRoot)source, (V)newValue); }
			@Override public Object without(Object source, Reference<?> ref) { return without.apply((TestRoot)source); }

			/**
			 * As a convenience, getter is allowed to return null in lieu of throwing NonexistentEntryException
			 */
			private Object throwIfNull(Object obj) throws NonexistentEntryException {
				if (obj == null) {
					throw new NonexistentEntryException(Path.just("UNAVAILABLE"));
				} else {
					return obj;
				}
			}

		};
	}

	private <V> Dereferencer fieldDereferencer(Function<TestRoot, V> get, BiFunction<TestRoot, V, TestRoot> with) {
		return dereferencer(get, with, s -> {
			throw new IllegalArgumentException("Cant delete field");
		});
	}

	private Iterable<Identifier> ids(String... strings) {
		return Stream.of(strings).map(Identifier::from)::iterator;
	}

}
