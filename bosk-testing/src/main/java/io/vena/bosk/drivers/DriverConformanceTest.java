package io.vena.bosk.drivers;

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
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.drivers.state.TestValues;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;

import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.util.Classes.listValue;
import static io.vena.bosk.util.Classes.mapValue;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertThrows;

public abstract class DriverConformanceTest extends AbstractDriverTest {
	// Subclass can initialize this as desired
	protected DriverFactory<TestEntity> driverFactory;

	@ParametersByName
	void testInitialState(Path enclosingCatalogPath) {
		initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceIdentical(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), Optional.of(newEntity(childID, ref)));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceDifferent(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(childID), Optional.of(newEntity(childID, ref)
			.withString("replaced")));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceWholeThenParts(Path enclosingCatalogPath, Identifier childID) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier awkwardID = Identifier.from(AWKWARD_ID);
		Reference<TestEntity> wholeEntityRef = catalogRef.then(awkwardID);
		CatalogReference<TestEntity> innerCatalogRef = wholeEntityRef.thenCatalog(TestEntity.class, "catalog");
		Reference<TestEntity> part1EntityRef = innerCatalogRef.then(childID);
		Reference<TestEntity> part2EntityRef = wholeEntityRef.thenSideTable(TestEntity.class, TestEntity.class, "sideTable").then(childID);
		Reference<ListingEntry> listingEntryRef = wholeEntityRef.thenListing(TestEntity.class, "listing").then(childID);

		driver.submitReplacement(wholeEntityRef,
			Optional.of(newEntity(awkwardID, catalogRef)
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
				))));
		driver.submitReplacement(part1EntityRef,
				Optional.of(emptyEntityAt(part1EntityRef)
				.withString("replaced-part1")));
		driver.submitReplacement(part2EntityRef,
				Optional.of(emptyEntityAt(part2EntityRef)
				.withString("replaced-part2")));
		driver.submitReplacement(listingEntryRef, Optional.of(LISTING_ENTRY));

		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDelete(Path enclosingCatalogPath, Identifier childID) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(childID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceCatalog(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier unique = Identifier.unique("child");
		driver.submitReplacement(ref, Optional.of(Catalog.of(
			newEntity(child2ID, ref),
			newEntity(unique, ref),
			newEntity(child1ID, ref)
		)));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceCatalogEmpty(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref, Optional.of(Catalog.empty()));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testConditionalReplaceFirst(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		// Self ID matches
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		// Self ID does not match
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		// Other ID matches
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 1"),
			child2IDRef, child2ID
		);
		assertCorrectBoskContents();

		// Other ID does not match
		driver.submitConditionalReplacement(
			ref.then(child1ID), newEntity(child1ID, ref).withString("replacement 2"),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@ParametersByName
	void testDeleteForward(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDeleteBackward(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testConditionalDelete(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<Identifier> child1IDRef = ref.then(child1ID).then(Identifier.class, TestEntity.Fields.id);
		Reference<Identifier> child2IDRef = ref.then(child2ID).then(Identifier.class, TestEntity.Fields.id);

		// Self ID does not match - should have no effect
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child2ID
		);
		assertCorrectBoskContents();

		// Other ID does not match - should have no effect
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child2IDRef, child1ID
		);
		assertCorrectBoskContents();

		// Other ID matches - child2 should disappear
		driver.submitConditionalDeletion(
			ref.then(child2ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

		// Self ID matches - child1 should disappear
		driver.submitConditionalDeletion(
			ref.then(child1ID),
			child1IDRef, child1ID
		);
		assertCorrectBoskContents();

	}

	@ParametersByName
	void testDeleteNonexistent(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(Identifier.from("nonexistent")).then(TestEntity.class,TestEntity.Fields.catalog, "nonexistent2"));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDeleteCatalog_fails(Path enclosingCatalogPath) {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		assertThrows(IllegalArgumentException.class, ()->
			driver.submitDeletion(ref));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDeleteFields_fails(Path enclosingCatalogPath) throws InvalidTypeException {
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
	void testOptional() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		assertCorrectBoskContents();
		driver.submitReplacement(ref, Optional.of(TestValues.blank().withString("changed")));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(ref, null));
		assertCorrectBoskContents();

		driver.submitDeletion(ref);
		assertCorrectBoskContents();
		driver.submitDeletion(ref);
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testString() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<String> stringRef = ref.then(String.class, TestValues.Fields.string);
		driver.submitReplacement(stringRef, Optional.of("changed"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(stringRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(stringRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testEnum() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<ChronoUnit> enumRef = ref.then(ChronoUnit.class, TestValues.Fields.chronoUnit);
		driver.submitReplacement(enumRef, Optional.of(MINUTES));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(enumRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(enumRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testListValue() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<ListValue<String>> listRef = ref.then(listValue(String.class), TestValues.Fields.list);
		driver.submitReplacement(listRef, Optional.of(ListValue.of("this", "that")));
		assertCorrectBoskContents();
		driver.submitReplacement(listRef, Optional.of(ListValue.of("that", "this")));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(listRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(listRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testMapValue() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<MapValue<String>> mapRef = ref.then(mapValue(String.class), TestValues.Fields.map);

		// Check that key order is preserved
		driver.submitReplacement(mapRef, Optional.of(MapValue.fromFunction(asList("key1", "key2"), key->key+"_value")));
		assertCorrectBoskContents();
		driver.submitReplacement(mapRef, Optional.of(MapValue.fromFunction(asList("key2", "key1"), key->key+"_value")));
		assertCorrectBoskContents();

		// Check that blank keys and values are supported
		driver.submitReplacement(mapRef, Optional.of(MapValue.singleton("", "")));
		assertCorrectBoskContents();

		// Check that value-only replacement works, even if the key has periods in it.
		// (Not gonna lie... this is motivated by MongoDriver. But really all drivers should handle this case,
		// so it makes sense to put it here. We're trying to trick MongoDB into confusing a key with dots for
		// a series of nested fields.)
		MapValue<String> originalMapValue = MapValue.fromFunction(asList("key.with.dots.1", "key.with.dots.2"), k -> k + "_originalValue");
		driver.submitReplacement(mapRef, Optional.of(originalMapValue));
		assertCorrectBoskContents();
		MapValue<String> newMapValue = originalMapValue.with("key.with.dots.1", "newValue");
		driver.submitReplacement(mapRef, Optional.of(newMapValue));
		assertCorrectBoskContents();

		// Check that the right submission-time exceptions are thrown
		assertThrows(NullPointerException.class, ()->driver.submitReplacement(mapRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(mapRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testFlushNothing() throws IOException, InterruptedException {
		setupBosksAndReferences(driverFactory);
		// Flush before any writes should work
		driver.flush();
		assertCorrectBoskContents();
	}

	private Reference<TestValues> initializeBoskWithBlankValues(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<TestValues> ref = catalogRef.then(child1ID).then(TestValues.class,
			TestEntity.Fields.values);
		driver.submitReplacement(ref, Optional.of(TestValues.blank()));
		return ref;
	}

	private CatalogReference<TestEntity> initializeBoskWithCatalog(Path enclosingCatalogPath) {
		setupBosksAndReferences(driverFactory);
		try {
			CatalogReference<TestEntity> ref = bosk.catalogReference(TestEntity.class, enclosingCatalogPath);

			TestEntity child1 = autoInitialize(ref.then(child1ID));
			TestEntity child2 = autoInitialize(ref.then(child2ID));

			Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
			driver.submitReplacement(ref, Optional.of(bothChildren));

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
			Path.of(TestEntity.Fields.sideTable, AWKWARD_ID, TestEntity.Fields.catalog, "parent", TestEntity.Fields.catalog)
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

}
