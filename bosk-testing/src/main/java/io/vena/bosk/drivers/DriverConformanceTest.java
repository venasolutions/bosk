package io.vena.bosk.drivers;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.DriverFactory;
import io.vena.bosk.Identifier;
import io.vena.bosk.ListValue;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.MapValue;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTable;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.drivers.state.TestValues;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.util.Classes.listValue;
import static io.vena.bosk.util.Classes.mapValue;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the basic functionality of {@link io.vena.bosk.BoskDriver}
 * across a variety of state tree situations by performing some series
 * of operations and then asserting that the resulting state matches
 * that computed by {@link Bosk#simpleDriver} performing the same operations.
 * <p>
 *
 * Use this by extending it and supplying a value for
 * the {@link #driverFactory} to test.
 */
public abstract class DriverConformanceTest extends AbstractDriverTest {
	// Subclass can initialize this as desired
	protected DriverFactory<TestEntity> driverFactory;

	public interface Refs {
		@ReferencePath("/id") Reference<Identifier> rootID();
		@ReferencePath("/catalog/-id-") Reference<TestEntity> catalogEntry(Identifier id);
	}

	@ParametersByName
	void initialState(Path enclosingCatalogPath) {
		initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
	}

	@ParametersByName
	void replaceIdentical(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), newEntity(childID, ref));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void replaceDifferent(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), newEntity(childID, ref)
			.withString("replaced"));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void replaceWholeThenParts(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier awkwardID = Identifier.from(AWKWARD_ID);
		Reference<TestEntity> wholeEntityRef = catalogRef.then(awkwardID);
		CatalogReference<TestEntity> innerCatalogRef = wholeEntityRef.thenCatalog(TestEntity.class, "catalog");
		Reference<TestEntity> part1EntityRef = innerCatalogRef.then(childID);
		Reference<TestEntity> part2EntityRef = wholeEntityRef.thenSideTable(TestEntity.class, TestEntity.class, "sideTable").then(childID);
		Reference<ListingEntry> listingEntryRef = wholeEntityRef.thenListing(TestEntity.class, "listing").then(childID);

		driver.submitReplacement(wholeEntityRef,
			newEntity(awkwardID, catalogRef)
				.withCatalog(Catalog.of(
					emptyEntityAt(part1EntityRef)
						.withString("original-part1")
				))
				.withSideTable(SideTable.of(innerCatalogRef,
					child1ID,
					emptyEntityAt(part2EntityRef)
						.withString("original-part2")
				))
				.withListing(Listing.of(innerCatalogRef,
					child1ID
				)));
		driver.submitReplacement(part1EntityRef,
			emptyEntityAt(part1EntityRef)
				.withString("replaced-part1"));
		driver.submitReplacement(part2EntityRef,
			emptyEntityAt(part2EntityRef)
				.withString("replaced-part2"));
		driver.submitReplacement(listingEntryRef, LISTING_ENTRY);

		assertCorrectBoskContents();
	}

	@ParametersByName
	void deleteExisting(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		// Here, we surgically initialize just the one child we want to delete, for a little variety.
		// Once upon a time, MongoDriver failed this specific case.
		Identifier childID = Identifier.unique("child");
		autoInitialize(ref.then(childID));
		driver.submitDeletion(ref.then(childID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void replaceCatalog(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier unique = Identifier.unique("child");
		driver.submitReplacement(ref, Catalog.of(
			newEntity(child2ID, ref),
			newEntity(unique, ref),
			newEntity(child1ID, ref)
		));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void replaceCatalogEmpty(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref, Catalog.empty());
		assertCorrectBoskContents();
	}

	@ParametersByName
	void conditionalReplaceFirst(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		LOGGER.debug("Self ID matches");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Self ID does not match");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID matches");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child2IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID does not match");
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@ParametersByName
	void deleteForward(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void deleteBackward(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
		LOGGER.debug("Delete second child");
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
		LOGGER.debug("Delete first child");
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void conditionalDelete(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		LOGGER.debug("Self ID does not match - should have no effect");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID does not match - should have no effect");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Other ID matches - child2 should disappear");
		driver.submitConditionalDeletion(
			ref.then(child2ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		LOGGER.debug("Self ID matches - child1 should disappear");
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@ParametersByName
	void replaceNonexistentField(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(
			ref.then(String.class, "nonexistent", "string"),
			"new value");
		assertCorrectBoskContents();
		driver.submitReplacement(
			ref.then(String.class, "nonexistent", TestEntity.Fields.catalog, "nonexistent2", "string"),
			"new value");
		assertCorrectBoskContents();
	}

	@ParametersByName
	void deleteNonexistent(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")).then(TestEntity.class,TestEntity.Fields.catalog, "nonexistent2"));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void deleteCatalog_fails(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		assertThrows(IllegalArgumentException.class, ()->
			driver.submitDeletion(ref));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void deleteFields_fails(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		// Use loops instead of parameters to avoid unnecessarily creating and initializing
		// a new bosk for every case. None of them affect the bosk anyway.
		for (Identifier childID: childID().collect(toList())) {
			for (String field: testEntityField().collect(toList())) {
				Reference<Object> target = ref.then(Object.class, childID.toString(), field);
				assertThrows(IllegalArgumentException.class, () ->
					driver.submitDeletion(target), "Must not allow deletion of field " + target);
				assertCorrectBoskContents();
			}
		}
	}

	@ParametersByName
	void optional() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();
		driver.submitReplacement(ref, TestValues.blank().withString("changed"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(ref, null));
		assertCorrectBoskContents();

		LOGGER.debug("Deleting {}", ref);
		driver.submitDeletion(ref);
		assertCorrectBoskContents();
		driver.submitDeletion(ref);
		assertCorrectBoskContents();
	}

	@ParametersByName
	void string() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<String> stringRef = ref.then(String.class, TestValues.Fields.string);
		LOGGER.debug("Submitting changed string");
		driver.submitReplacement(stringRef, "changed");
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(stringRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(stringRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void enumeration() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();
		Reference<ChronoUnit> enumRef = ref.then(ChronoUnit.class, TestValues.Fields.chronoUnit);
		driver.submitReplacement(enumRef, MINUTES);
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(enumRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(enumRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void listValue_works() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<ListValue<String>> listRef = ref.then(listValue(String.class), TestValues.Fields.list);
		driver.submitReplacement(listRef, ListValue.of("this", "that"));
		assertCorrectBoskContents();
		driver.submitReplacement(listRef, ListValue.of("that", "this"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(listRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(listRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void mapValue_works() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<MapValue<String>> mapRef = ref.then(mapValue(String.class), TestValues.Fields.map);

		// Check that key order is preserved
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key1", "key2"), key->key+"_value"));
		assertCorrectBoskContents();
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key2", "key1"), key->key+"_value"));
		assertCorrectBoskContents();

		// Check that blank keys and values are supported
		driver.submitReplacement(mapRef, MapValue.singleton("", ""));
		assertCorrectBoskContents();

		// Check that value-only replacement works, even if the key has periods in it.
		// (Not gonna lie... this is motivated by MongoDriver. But really all drivers should handle this case,
		// so it makes sense to put it here. We're trying to trick MongoDB into confusing a key with dots for
		// a series of nested fields.)
		MapValue<String> originalMapValue = MapValue.fromFunction(asList("key.with.dots.1", "key.with.dots.2"), k -> k + "_originalValue");
		driver.submitReplacement(mapRef, originalMapValue);
		assertCorrectBoskContents();
		MapValue<String> newMapValue = originalMapValue.with("key.with.dots.1", "newValue");
		driver.submitReplacement(mapRef, newMapValue);
		assertCorrectBoskContents();

		// Check that the right submission-time exceptions are thrown
		assertThrows(NullPointerException.class, ()->driver.submitReplacement(mapRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(mapRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void flushNothing() throws IOException, InterruptedException {
		setupBosksAndReferences(driverFactory);
		// Flush before any writes should work
		driver.flush();
		assertCorrectBoskContents();
	}

	@ParametersByName
	void submitReplacement_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<String> ref = bosk.rootReference().then(String.class, "string");
		testDiagnosticContextPropagation(() -> bosk.driver().submitReplacement(ref, "value1"));
		// Do a second one with the same diagnostics to verify that they
		// still propagate even when they don't change
		testDiagnosticContextPropagation(() -> bosk.driver().submitReplacement(ref, "value2"));
	}

	@ParametersByName
	void submitConditionalReplacement_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<String> ref = bosk.rootReference().then(String.class, "string");
		testDiagnosticContextPropagation(() -> bosk.driver().submitConditionalReplacement(ref, "newValue", refs.rootID(), Identifier.from("root")));
	}

	@ParametersByName
	void submitInitialization_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Identifier id = Identifier.from("testEntity");
		Reference<TestEntity> ref = refs.catalogEntry(id);
		testDiagnosticContextPropagation(() -> bosk.driver().submitInitialization(ref, emptyEntityAt(ref)));
	}

	@ParametersByName
	void submitDeletion_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> ref = refs.catalogEntry(Identifier.unique("e"));
		autoInitialize(ref);
		testDiagnosticContextPropagation(() -> bosk.driver().submitDeletion(ref));
	}

	@ParametersByName
	void submitConditionalDeletion_propagatesDiagnosticContext() throws InvalidTypeException, IOException, InterruptedException {
		initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Refs refs = bosk.buildReferences(Refs.class);
		Reference<TestEntity> ref = refs.catalogEntry(Identifier.unique("e"));
		autoInitialize(ref);
		testDiagnosticContextPropagation(() -> bosk.driver().submitConditionalDeletion(ref, refs.rootID(), Identifier.from("root")));
	}

	private void testDiagnosticContextPropagation(Runnable operation) throws IOException, InterruptedException {
		AtomicBoolean diagnosticsAreReady = new AtomicBoolean(false);
		Semaphore diagnosticsVerified = new Semaphore(0);
		bosk.registerHook("contextPropagatesToHook", bosk.rootReference(), ref -> {
			// Note that this will run as soon as it's registered
			if (diagnosticsAreReady.get()) {
				assertEquals("attributeValue", bosk.diagnosticContext().getAttribute("attributeName"));
				diagnosticsVerified.release();
			}
		});
		bosk.driver().flush();
		try (var __ = bosk.diagnosticContext().withAttribute("attributeName", "attributeValue")) {
			diagnosticsAreReady.set(true);
			LOGGER.debug("Running operation with diagnostic context");
			operation.run();
		}
		assertCorrectBoskContents();
		assertTrue(diagnosticsVerified.tryAcquire(5, SECONDS));
		diagnosticsAreReady.set(false); // Deactivate the hook
	}

	private Reference<TestValues> initializeBoskWithBlankValues(Path enclosingCatalogPath) throws InvalidTypeException {
		LOGGER.debug("initializeBoskWithBlankValues({})", enclosingCatalogPath);
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<TestValues> ref = catalogRef.then(child1ID).then(TestValues.class,
			TestEntity.Fields.values);
		driver.submitReplacement(ref, TestValues.blank());
		return ref;
	}

	private CatalogReference<TestEntity> initializeBoskWithCatalog(Path enclosingCatalogPath) {
		LOGGER.debug("initializeBoskWithCatalog({})", enclosingCatalogPath);
		setupBosksAndReferences(driverFactory);
		try {
			CatalogReference<TestEntity> ref = bosk.rootReference().thenCatalog(TestEntity.class, enclosingCatalogPath);

			TestEntity child1 = autoInitialize(ref.then(child1ID));
			TestEntity child2 = autoInitialize(ref.then(child2ID));

			Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
			driver.submitReplacement(ref, bothChildren);

			return ref;
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Note that we don't use this in every test. The idea is that some of them are
	 * not worth the time to run multiple times with different enclosing catalogs,
	 * because it's not really credible that the test would fail with one of these
	 * and pass with another.
	 */
	@SuppressWarnings("unused")
	static Stream<Path> enclosingCatalogPath() {
		return Stream.of(
			Path.just(TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.catalog, AWKWARD_ID, TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.catalog, "parent", TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.sideTable, "parent", TestEntity.Fields.catalog)
		);
	}

	@SuppressWarnings("unused")
	static Stream<Identifier> childID() {
		return Stream.of(
			"child1",
			"child2",
			"nonexistent",
			"id.with.dots",
			"id/with/slashes",
			"$id$with$dollars$",
			AWKWARD_ID,
			"idWithEmojis\uD83C\uDF33\uD83E\uDDCA"
		).map(Identifier::from);
	}

	/**
	 * Contains all kinds of special characters
	 */
	public static final String AWKWARD_ID = "$id.with%everything/ +\uD83D\uDE09";

	@SuppressWarnings("unused")
	static Stream<String> testEntityField() {
		return Stream.of(
			TestEntity.Fields.id,
			TestEntity.Fields.string,
			TestEntity.Fields.catalog,
			TestEntity.Fields.listing,
			TestEntity.Fields.sideTable
		);
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(DriverConformanceTest.class);
}
