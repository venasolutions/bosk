package io.vena.bosk;

import io.vena.bosk.Bosk.ReadContext;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.NonexistentReferenceException;
import io.vena.bosk.util.Classes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.Delegate;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.ReferenceUtils.rawClass;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests that the Bosk can supply references that point to the right things, and
 * that its local driver performs updates and deletes that preserve the exact
 * objects supplied.
 *
 * <p>
 * Note that {@link BoskDriver} does not, in general, preserve the actual Java
 * objects supplied; it can replace them with equivalent objects, having all the
 * same fields with equivalent values.  So the tests in here are not useful
 * tests of {@link BoskDriver} in general.  We should probably modify these
 * tests so that they use assertEquals instead of assertSame; then they should
 * pass for any driver, and we could use {@link AbstractRoundTripTest} and
 * similar to apply these tests to all kinds of drivers.
 *
 * @author Patrick Doyle
 *
 */
class BoskTest {
	Bosk<Root> bosk;
	Root root;
	CatalogReference<TestEntity> entitiesRef;

	@BeforeEach
	void initializeBosk() throws InvalidTypeException {
		Root initialRoot = new Root(Identifier.from("root"), 1, Catalog.empty());
		bosk = new Bosk<>(BOSK_NAME, Root.class, initialRoot, Bosk::simpleDriver);
		entitiesRef = bosk.rootReference().thenCatalog(TestEntity.class, Root.Fields.entities);
		Identifier ernieID = Identifier.from("ernie");
		Identifier bertID = Identifier.from("bert");
		TestEntity ernie = new TestEntity(ernieID, 1,
				bosk.reference(TestEntity.class, Path.of(Root.Fields.entities, bertID.toString())),
				Catalog.empty(),
				Listing.of(entitiesRef, bertID),
				SideTable.of(entitiesRef, bertID, "buddy"),
				ListValue.empty(),
				Optional.empty());
		TestEntity bert = new TestEntity(bertID, 1,
				bosk.reference(TestEntity.class, Path.of(Root.Fields.entities, ernieID.toString())),
				Catalog.empty(),
				Listing.of(entitiesRef, ernieID),
				SideTable.of(entitiesRef, ernieID, "pal"),
				ListValue.empty(),
				Optional.empty());
		bosk.driver().submitReplacement(entitiesRef, Catalog.of(ernie, bert));
		root = bosk.currentRoot();
	}

	@Accessors(fluent=true) @Getter @With @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	@EqualsAndHashCode(callSuper = false) @ToString @FieldNameConstants
	public static class Root implements Entity {
		Identifier id;
		int version;
		Catalog<TestEntity> entities;
	}

	@Accessors(fluent=true) @Getter @With @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	@EqualsAndHashCode(callSuper = false) @ToString @FieldNameConstants
	public static class TestEntity implements Entity {
		Identifier id;
		int version;
		Reference<TestEntity> refField;
		Catalog<TestEntity> catalog;
		Listing<TestEntity> listing;
		SideTable<TestEntity, String> sideTable;
		ListValue<String> listValue;
		Optional<TestEntity> optional;
	}

	@Test
	void testRootReference() throws Exception {
		checkReferenceProperties(bosk.rootReference(), Path.empty(), root);
		checkUpdates(bosk.rootReference(), ROOT_UPDATER);
		assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(bosk.rootReference()));
	}

	@Test
	void testCatalogReference() throws Exception {
		@SuppressWarnings({ "rawtypes", "unchecked" })
		Class<Catalog<TestEntity>> catalogClass = (Class<Catalog<TestEntity>>)(Class)Catalog.class;
		Path entitiesPath = Path.just(Root.Fields.entities);
		List<Reference<Catalog<TestEntity>>> refs = asList(
				bosk.reference(catalogClass, entitiesPath),
				bosk.catalogReference(TestEntity.class, entitiesPath),
				bosk.rootReference().thenCatalog(TestEntity.class, Root.Fields.entities));
		for (Reference<Catalog<TestEntity>> catalogRef: refs) {
			checkReferenceProperties(catalogRef, entitiesPath, root.entities());
			for (Identifier id: root.entities.ids()) {
				Reference<TestEntity> entityRef = catalogRef.then(TestEntity.class, id.toString());
				TestEntity expectedValue = root.entities().get(id);
				checkEntityReference(entityRef, Path.of(Root.Fields.entities, id.toString()), expectedValue);
				checkUpdates(entityRef, ENTITY_UPDATER);
				checkDeletion(entityRef, expectedValue);
			}
			checkEntityReference(catalogRef.then(TestEntity.class, "nonexistent"), Path.of(Root.Fields.entities, "nonexistent"), null);
		}
		// TODO: do the nested Catalog in TestEntity
	}

	@Test
	void testListingReference() throws Exception {
		for (Identifier id: root.entities.ids()) {
			Path listingPath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.listing);
			List<ListingReference<TestEntity>> refs = asList(
					bosk.listingReference(TestEntity.class, listingPath),
					bosk.rootReference().thenListing(TestEntity.class, Root.Fields.entities, id.toString(), TestEntity.Fields.listing),
					bosk.rootReference().thenListing(TestEntity.class, Root.Fields.entities, "-entity-", TestEntity.Fields.listing).boundTo(id)
					);
			for (ListingReference<TestEntity> listingRef: refs) {
				// Check the Listing reference itself
				try {
					checkReferenceProperties(listingRef, listingPath, root.entities().get(id).listing());
					assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(listingRef));
				} catch (AssertionError e) {
					throw new AssertionError("Failed checks on listingRef " + listingRef + ": " + e.getMessage(), e);
				}

				// Check references to the Listing contents
				Listing<TestEntity> listing;
				Map<Identifier, TestEntity> entries;
				try (ReadContext context = bosk.readContext()) {
					listing = listingRef.value();
					entries = listing.valueMap();
				}
				for (Identifier entryID: listing.ids()) {
					Reference<ListingEntry> entryRef = listingRef.then(entryID);
					Path entryPath = listingPath.then(entryID.toString());
					try {
						checkReferenceProperties(entryRef, entryPath, LISTING_ENTRY);
						checkDeletion(entryRef, LISTING_ENTRY);
						// Note: updates through Listing references don't affect
						// the referenced entity, so checkReferenceUpdates is
						// not appropriate here.
						// TODO: Add a test to verify this
					} catch (AssertionError e) {
						throw new AssertionError("Failed checks on entryRef " + entryRef + ": " + e.getMessage(), e);
					}
				}

				Identifier nonexistent = Identifier.from("nonexistent");
				Reference<ListingEntry> entryRef = listingRef.then(nonexistent);
				checkReferenceProperties(entryRef, listingPath.then("nonexistent"), null);
				checkDeletion(entryRef, null);
			}
		}
	}

	@Test
	void testSideTableReference() throws InvalidTypeException {
		for (Identifier id: root.entities.ids()) {
			Path sideTablePath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.sideTable);
			List<SideTableReference<TestEntity,String>> refs = asList(
					bosk.sideTableReference(TestEntity.class, String.class, sideTablePath),
					bosk.rootReference().thenSideTable(TestEntity.class, String.class, Root.Fields.entities, id.toString(), TestEntity.Fields.sideTable),
					bosk.rootReference().thenSideTable(TestEntity.class, String.class, Root.Fields.entities, "-entity-", TestEntity.Fields.sideTable).boundTo(id)
					);
			for (SideTableReference<TestEntity,String> sideTableRef: refs) {
				SideTable<TestEntity, String> sideTable = root.entities().get(id).sideTable();
				try {
					checkReferenceProperties(sideTableRef, sideTablePath, sideTable);
				} catch (AssertionError e) {
					throw new AssertionError("Failed checkRefence on id " + id + ", sideTableRef " + sideTableRef);
				}
				try (ReadContext context = bosk.readContext()) {
					for (Entry<Identifier, String> entry: sideTable.idEntrySet()) {
						Identifier key = entry.getKey();
						Reference<String> entryRef = sideTableRef.then(key);
						String expectedValue = entry.getValue();
						String actualValue = entryRef.value();
						assertEquals(expectedValue, actualValue, entryRef.toString());
					}
				}

				Identifier nonexistent = Identifier.from("nonexistent");
				Reference<String> entryRef = sideTableRef.then(nonexistent);
				checkReferenceProperties(entryRef, sideTablePath.then("nonexistent"), null);
				checkDeletion(entryRef, null);
			}
		}
	}

	@Test
	void testReferenceReference() throws Exception {
		for (Identifier id: root.entities.ids()) {
			Path refPath = Path.of(Root.Fields.entities, id.toString(), TestEntity.Fields.refField);
			List<Reference<Reference<TestEntity>>> refs = asList(
					bosk.referenceReference(TestEntity.class, refPath),
					bosk.rootReference().thenReference(TestEntity.class, Root.Fields.entities, id.toString(), TestEntity.Fields.refField),
					bosk.rootReference().thenReference(TestEntity.class, Root.Fields.entities, "-entity-", TestEntity.Fields.refField).boundTo(id)
			);
			for (Reference<Reference<TestEntity>> ref: refs) {
				Reference<TestEntity> refField = root.entities().get(id).refField();
				try {
					checkReferenceProperties(ref, refPath, refField);
					checkUpdates(ref, this::refUpdater);
					assertThrows(IllegalArgumentException.class, ()->bosk.driver().submitDeletion(ref));
				} catch (AssertionError e) {
					throw new AssertionError("Failed checkRefence on id " + id + ", referenceRef " + ref, e);
				}
			}
		}
	}

	/**
	 * A regression test for a type checking bug.
	 */
	@Test
	void testBogusReferenceReference() {
		assertThrows(InvalidTypeException.class, ()->
			bosk.reference(Classes.reference(String.class), Path.empty())); // Root object isn't a reference to a String
	}

	@Test
	void testName() {
		assertEquals(BOSK_NAME, bosk.name());
	}

	@Test
	void testValidation() {
		@EqualsAndHashCode(callSuper = true)
		class InvalidRoot extends Root {
			@SuppressWarnings("unused")
			final String mutableString;

			public InvalidRoot(Identifier id, Catalog<TestEntity> entities, String str) {
				super(id, 0xdead, entities);
				this.mutableString = str;
			}
		}
		assertThrows(IllegalArgumentException.class, () -> new Bosk<>("invalid", InvalidRoot.class, new InvalidRoot(Identifier.unique("yucky"), Catalog.empty(), "hello"), Bosk::simpleDriver));
		assertThrows(IllegalArgumentException.class, () -> new Bosk<>("invalid", String.class, new InvalidRoot(Identifier.unique("yucky"), Catalog.empty(), "hello"), Bosk::simpleDriver));
	}

	@Test
	void testDriver() {
		// This doesn't test the operation of the driver; merely that the right driver is returned
		AtomicReference<BoskDriver<Root>> driver = new AtomicReference<>();
		Bosk<Root> myBosk = new Bosk<>("My bosk", Root.class, new Root(Identifier.unique("root"), 123, Catalog.empty()), (b,d) -> {
			BoskDriver<Root> bd = new ProxyDriver(d);
			driver.set(bd);
			return bd;
		});
		assertSame(driver.get(), myBosk.driver());
	}

	@RequiredArgsConstructor
	private static final class ProxyDriver implements BoskDriver<Root> {
		@Delegate final BoskDriver<Root> delegate;
	}

	private <T> void checkReferenceProperties(Reference<T> ref, Path expectedPath, T expectedValue) throws InvalidTypeException {
		if (expectedValue != null) {
			assertTrue(ref.targetClass().isAssignableFrom(expectedValue.getClass()));
			assertSame(ref.targetClass(), rawClass(ref.targetType()));
		}
		assertEquals(expectedPath, ref.path());
		assertEquals(expectedPath.urlEncoded(), ref.pathString());

		assertThrows(IllegalStateException.class, ref::value, "Can't read before ReadContext");
		try (ReadContext context = bosk.readContext()) {
			T actualValue = ref.valueIfExists();
			assertSame(expectedValue, actualValue);

			if (expectedValue == null) {
				assertFalse(ref.exists());
				assertThrows(NonexistentReferenceException.class, ref::value);
				assertFalse(ref.optionalValue().isPresent());
			} else {
				assertTrue(ref.exists());
				assertSame(expectedValue, ref.value());
				assertSame(expectedValue, ref.optionalValue().get());
			}
		}
		assertThrows(IllegalStateException.class, ref::value, "Can't read after ReadContext");
	}

	private void checkEntityReference(Reference<TestEntity> ref, Path expectedPath, TestEntity expectedValue) throws InvalidTypeException {
		checkReferenceProperties(ref, expectedPath, expectedValue);

		assertEquals(Path.empty(), ref.enclosingReference(Root.class).path());

		// All kinds of "then" variants

		assertEquals(expectedPath.then(TestEntity.Fields.catalog), ref.thenCatalog(TestEntity.class, TestEntity.Fields.catalog).path());
		assertEquals(expectedPath.then(TestEntity.Fields.listing), ref.thenListing(TestEntity.class, TestEntity.Fields.listing).path());
		assertEquals(expectedPath.then(TestEntity.Fields.sideTable), ref.thenSideTable(TestEntity.class, String.class, TestEntity.Fields.sideTable).path());

		assertEquals(expectedPath.then(TestEntity.Fields.catalog), ref.then(Catalog.class, TestEntity.Fields.catalog).path());
		assertEquals(expectedPath.then(TestEntity.Fields.listing), ref.then(Listing.class, TestEntity.Fields.listing).path());
		assertEquals(expectedPath.then(TestEntity.Fields.sideTable), ref.then(SideTable.class, TestEntity.Fields.sideTable).path());

		try (ReadContext context = bosk.readContext()) {
			if (expectedValue == null) {
				assertEquals(null, ref.then(Catalog.class, TestEntity.Fields.catalog).valueIfExists());
				assertEquals(null, ref.then(Listing.class, TestEntity.Fields.listing).valueIfExists());
				assertEquals(null, ref.then(SideTable.class, TestEntity.Fields.sideTable).valueIfExists());
			} else {
				assertEquals(expectedValue.catalog(), ref.then(Catalog.class, TestEntity.Fields.catalog).value());
				assertEquals(expectedValue.listing(), ref.then(Listing.class, TestEntity.Fields.listing).value());
				assertEquals(expectedValue.sideTable(), ref.then(SideTable.class, TestEntity.Fields.sideTable).value());
			}
		}
	}

	private <T> void checkUpdates(Reference<T> ref, UnaryOperator<T> updater) throws InterruptedException, ExecutionException {
		Root originalRoot;
		T firstValue;
		assertThrows(IllegalStateException.class, ref::value, "Can't read from Bosk before ReadContext");
		try (ReadContext context = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			firstValue = ref.value();
		}
		assertThrows(IllegalStateException.class, ref::value, "Can't read from Bosk between ReadContexts");

		T secondValue = updater.apply(firstValue);
		try (ReadContext context = bosk.readContext()) {
			assertSame(firstValue, ref.value(), "New ReadContext sees same value as before");
			bosk.driver().submitReplacement(ref, secondValue);
			assertSame(firstValue, ref.value(), "Bosk updates not visible during the same ReadContext");
		}

		try (ReadContext context = bosk.readContext()) {
			assertSame(secondValue, ref.value(), "New value is visible in next ReadContext");
			bosk.driver().submitReplacement(ref, firstValue);
			assertSame(secondValue, ref.value(), "Bosk updates still not visible during the same ReadContext");
			ExecutorService executor = Executors.newFixedThreadPool(1);
			Future<?> future = executor.submit(()->{
				IllegalStateException caught = null;
				try {
					ref.value();
				} catch (IllegalStateException e) {
					caught = e;
				} catch (Throwable e) {
					fail("Unexpected exception: ", e);
				}
				assertNotNull(caught, "New thread should not have any scope by default, so an exception should be thrown");
				try (ReadContext unrelatedContext = bosk.readContext()) {
					assertSame(firstValue, ref.value(), "Separate thread should see the latest state");
				}
				try (ReadContext inheritedContext = context.adopt()) {
					assertSame(secondValue, ref.value(), "Inherited scope should see the same state");

					try (ReadContext reinheritedContext = inheritedContext.adopt()) {
						// Harmless to re-assert a scope you're already in
						assertSame(secondValue, ref.value(), "Inner scope should see the same state");
					}
				}
			});
			future.get();
		}

		try (ReadContext context = bosk.readContext()) {
			assertSame(firstValue, ref.value(), "Referenced item is restored to its original state");
		}

		// Reset the bosk for subsequent tests.  This is necessary because we do
		// a lot of strong assertSame checks, and so it's not good enough to
		// leave it in an "equivalent" state; it must be composed of the same objects.
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);
	}

	private <T> void checkDeletion(Reference<T> ref, T expectedValue) {
		Root originalRoot;
		try (ReadContext context = bosk.readContext()) {
			originalRoot = bosk.rootReference().value();
			assertSame(expectedValue, ref.valueIfExists(), "Value is present before deletion");
			bosk.driver().submitDeletion(ref);
			assertSame(expectedValue, ref.valueIfExists(), "Bosk deletions not visible during the same ReadContext");
		}
		try (ReadContext context = bosk.readContext()) {
			assertThrows(NonexistentReferenceException.class, ref::value);
			if (expectedValue != null) {
				bosk.driver().submitReplacement(ref, expectedValue);
				assertThrows(NonexistentReferenceException.class, ref::value);
			}
		}
		bosk.driver().submitReplacement(bosk.rootReference(), originalRoot);
	}

	private static final UnaryOperator<Root>       ROOT_UPDATER   = r -> r.withVersion(1 + r.version());
	private static final UnaryOperator<TestEntity> ENTITY_UPDATER = e -> e.withVersion(1 + e.version());

	private Reference<TestEntity> refUpdater(Reference<TestEntity> ref) {
		List<String> pathSegments = new ArrayList<>(ref.path().segmentStream().collect(toList()));
		pathSegments.set(1, "REPLACED_ID"); // second segment is the entity ID
		try {
			return bosk.reference(ref.targetClass(), Path.of(pathSegments));
		} catch (InvalidTypeException e) {
			throw new AssertionError("Unexpected!", e);
		}
	}

	private static final String BOSK_NAME = "bosk name";
}
