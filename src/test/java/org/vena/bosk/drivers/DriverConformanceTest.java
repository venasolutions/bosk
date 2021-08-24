package org.vena.bosk.drivers;

import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Catalog;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Identifier;
import org.vena.bosk.ListValue;
import org.vena.bosk.MapValue;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.junit.ParametersByName;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.vena.bosk.util.Classes.listValue;
import static org.vena.bosk.util.Classes.mapValue;

public abstract class DriverConformanceTest extends AbstractDriverTest {
	// Subclass can initialize this as desired
	BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>> driverFactory;

	@ParametersByName
	void testInitialState(Path enclosingCatalogPath) throws InvalidTypeException {
		initializeBoskWithCatalog(enclosingCatalogPath);
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceIdentical(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(child1ID), newEntity(child1ID, ref));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceFirst(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(child1ID), newEntity(child1ID, ref)
			.withString("replaced"));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceSecond(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref.then(child2ID), newEntity(child2ID, ref)
			.withString("replaced"));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceNonexistent(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		Identifier unique = Identifier.unique("child");
		driver.submitReplacement(ref.then(unique), newEntity(unique, ref));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testReplaceCatalog(Path enclosingCatalogPath) throws InvalidTypeException {
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
	void testReplaceCatalogEmpty(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitReplacement(ref, Catalog.empty());
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDeleteForward(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child1ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testDeleteBackward(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> ref = initializeBoskWithCatalog(enclosingCatalogPath);
		driver.submitDeletion(ref.then(child2ID));
		assertCorrectBoskContents();
		driver.submitDeletion(ref.then(child1ID));
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
	void testDeleteCatalog_fails(Path enclosingCatalogPath) throws InvalidTypeException {
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
		for (String childID: childID().collect(toList())) {
			for (String field: testEntityField().collect(toList())) {
				Reference<Object> target = ref.then(Object.class, childID, field);
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
		driver.submitReplacement(ref, TestValues.blank().withString("changed"));
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
		driver.submitReplacement(stringRef, "changed");
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(stringRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(stringRef));
		assertCorrectBoskContents();
	}

	@ParametersByName
	void testListValue() throws InvalidTypeException {
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
	void testMapValue() throws InvalidTypeException {
		Reference<TestValues> ref = initializeBoskWithBlankValues(Path.just(TestEntity.Fields.catalog));
		Reference<MapValue<String>> mapRef = ref.then(mapValue(String.class), TestValues.Fields.map);
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key1", "key2"), key->key+"_value"));
		assertCorrectBoskContents();
		driver.submitReplacement(mapRef, MapValue.fromFunction(asList("key2", "key1"), key->key+"_value"));
		assertCorrectBoskContents();

		assertThrows(NullPointerException.class, ()->driver.submitReplacement(mapRef, null));
		assertCorrectBoskContents();
		assertThrows(IllegalArgumentException.class, ()->driver.submitDeletion(mapRef));
		assertCorrectBoskContents();
	}

	private Reference<TestValues> initializeBoskWithBlankValues(Path enclosingCatalogPath) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = initializeBoskWithCatalog(enclosingCatalogPath);
		Reference<TestValues> ref = catalogRef.then(child1ID).then(TestValues.class,
			TestEntity.Fields.values);
		driver.submitReplacement(ref, TestValues.blank());
		return ref;
	}

	private CatalogReference<TestEntity> initializeBoskWithCatalog(Path enclosingCatalogPath) throws InvalidTypeException {
		setupBosksAndReferences(driverFactory);
		try {
			CatalogReference<TestEntity> ref = bosk.catalogReference(TestEntity.class, enclosingCatalogPath);

			TestEntity child1 = autoInitialize(ref.then(child1ID));
			TestEntity child2 = autoInitialize(ref.then(child2ID));

			Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
			driver.submitReplacement(ref, bothChildren);

			return ref;
		} catch (InvalidTypeException e) {
			throw new AssertionError(e);
		}
	}

	@SuppressWarnings("unused")
	static Stream<Path> enclosingCatalogPath() {
		return Stream.of(
			Path.just(TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.catalog, "parent", TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.mapping, "key1", TestEntity.Fields.catalog),
			Path.of(TestEntity.Fields.mapping, "key1", TestEntity.Fields.catalog, "parent", TestEntity.Fields.catalog)
		);
	}

	@SuppressWarnings("unused")
	static Stream<String> childID() {
		return Stream.of("child1", "child2", "nonexistent");
	}

	@SuppressWarnings("unused")
	static Stream<String> testEntityField() {
		return Stream.of(
			TestEntity.Fields.id,
			TestEntity.Fields.string,
			TestEntity.Fields.catalog,
			TestEntity.Fields.listing,
			TestEntity.Fields.mapping
		);
	}

}
