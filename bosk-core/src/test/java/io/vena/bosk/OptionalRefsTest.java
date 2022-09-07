package io.vena.bosk;

import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionalRefsTest extends AbstractRoundTripTest {
	private static final Identifier ID = Identifier.from("dummy");

	@Test
	void testReferenceOptionalNotAllowed() {
		Bosk<OptionalString> bosk = new Bosk<>("optionalNotAllowed", OptionalString.class, new OptionalString(ID, Optional.empty()), Bosk::simpleDriver);
		InvalidTypeException e = assertThrows(InvalidTypeException.class, () -> bosk.reference(Optional.class, Path.just("field")));
		assertThat(e.getMessage(), containsString("not supported"));
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalString(DriverFactory<OptionalString> driverFactory) throws InvalidTypeException {
		doTest(new OptionalString(ID, Optional.empty()), b->"HERE I AM", driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalString implements Entity {
		Identifier id;
		Optional<String> field;
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalEntity(DriverFactory<OptionalEntity> driverFactory) throws InvalidTypeException {
		OptionalEntity empty = new OptionalEntity(ID, Optional.empty());
		doTest(empty, b->empty, driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalEntity implements Entity {
		Identifier id;
		Optional<OptionalEntity> field;
	}

	//@ParameterizedTest // TODO: Reference<Reference<?>> is not yet supported
	@MethodSource("driverFactories")
	void testOptionalReference(DriverFactory<OptionalReference> driverFactory) throws InvalidTypeException {
		doTest(new OptionalReference(ID, Optional.empty()), Bosk::rootReference, driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalReference implements Entity {
		Identifier id;
		Optional<Reference<OptionalReference>> field;
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalCatalog(DriverFactory<OptionalCatalog> driverFactory) throws InvalidTypeException {
		OptionalCatalog empty = new OptionalCatalog(ID, Optional.empty());
		doTest(empty, b->Catalog.of(empty), driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalCatalog implements Entity {
		Identifier id;
		Optional<Catalog<OptionalCatalog>> field;
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalListing(DriverFactory<OptionalListing> driverFactory) throws InvalidTypeException {
		OptionalListing empty = new OptionalListing(ID, Catalog.empty(), Optional.empty());
		doTest(empty, b->Listing.of(b.rootReference().thenCatalog(OptionalListing.class, "catalog"), ID), driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalListing implements Entity {
		Identifier id;
		Catalog<OptionalListing> catalog;
		Optional<Listing<OptionalListing>> field;
	}

	@ParameterizedTest
	@MethodSource("driverFactories")
	void testOptionalSideTable(DriverFactory<OptionalSideTable> driverFactory) throws InvalidTypeException {
		OptionalSideTable empty = new OptionalSideTable(ID, Catalog.empty(), Optional.empty());
		doTest(empty, b-> SideTable.of(b.rootReference().thenCatalog(OptionalSideTable.class, "catalog"), ID, "Howdy"), driverFactory);
	}

	@EqualsAndHashCode(callSuper = false)
	@Accessors(fluent=true) @Getter @FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	public static class OptionalSideTable implements Entity {
		Identifier id;
		Catalog<OptionalSideTable> catalog;
		Optional<SideTable<OptionalSideTable, String>> field;
	}

	private interface ValueFactory<R extends Entity, V> {
		V createFrom(Bosk<R> bosk) throws InvalidTypeException;
	}

	private <E extends Entity, V> void doTest(E initialRoot, ValueFactory<E, V> valueFactory, DriverFactory<E> driverFactory) throws InvalidTypeException {
		Bosk<E> bosk = new Bosk<>("bosk", initialRoot.getClass(), initialRoot, driverFactory);
		V value = valueFactory.createFrom(bosk);
		@SuppressWarnings("unchecked")
		Reference<V> optionalRef = bosk.rootReference().then((Class<V>)value.getClass(), "field");
		try (val context = bosk.readContext()) {
			assertEquals(null, optionalRef.valueIfExists());
		}
		bosk.driver().submitReplacement(optionalRef, value);
		try (val context = bosk.readContext()) {
			assertEquals(value, optionalRef.valueIfExists());
		}
		bosk.driver().submitDeletion(optionalRef);
		try (val context = bosk.readContext()) {
			assertEquals(null, optionalRef.valueIfExists());
		}

		// Try other ways of getting the same reference
		@SuppressWarnings("unchecked")
		Reference<V> ref2 = bosk.reference((Class<V>)value.getClass(), Path.just("field"));
		assertEquals(optionalRef, ref2);
	}

}
