package org.vena.bosk.drivers;

import java.util.Optional;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.With;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;
import lombok.experimental.FieldNameConstants;
import org.vena.bosk.Bosk;
import org.vena.bosk.BoskDriver;
import org.vena.bosk.Catalog;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.ConfigurationNode;
import org.vena.bosk.Entity;
import org.vena.bosk.Identifier;
import org.vena.bosk.ListValue;
import org.vena.bosk.Listing;
import org.vena.bosk.ListingEntry;
import org.vena.bosk.MapValue;
import org.vena.bosk.Mapping;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.exceptions.InvalidTypeException;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AbstractDriverTest {
	protected final Identifier rootID = Identifier.from("root");
	protected final Identifier child1ID = Identifier.from("child1");
	protected final Identifier child2ID = Identifier.from("child2");
	protected final Identifier grandchild11ID = Identifier.from("grandchild11");
	protected final Identifier grandchild12ID = Identifier.from("grandchild12");
	protected Bosk<TestEntity> canonicalBosk;
	protected Bosk<TestEntity> bosk;
	protected BoskDriver<TestEntity> driver;
	protected TestEntity initialRoot;
	protected Reference<TestEntity> anyChild;
	protected Reference<TestEntity> anyGrandchild;
	protected CatalogReference<TestEntity> anyChildCatalog;
	protected CatalogReference<TestEntity> anyGrandchildCatalog;
	private Reference<ListingEntry> anyListingEntry;
	private Reference<TestEntity> anyMappingEntry;

	protected void setupBosksAndReferences(BiFunction<BoskDriver<TestEntity>, Bosk<TestEntity>, BoskDriver<TestEntity>> driverFactory) throws InvalidTypeException {
		// This is the bosk whose behaviour we'll consider to be correct by definition
		canonicalBosk = new Bosk<TestEntity>("Canonical bosk", TestEntity.class, this::initialRoot, Bosk::simpleDriver);

		// This is the bosk we're testing
		bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, (d,b) -> new ForwardingDriver<>(asList(
			new MirroringDriver<>(canonicalBosk),
			driverFactory.apply(d, b)
		)));
		driver = bosk.driver();

		anyChild = bosk.reference(TestEntity.class, Path.of(
			TestEntity.Fields.catalog, "-child-"));
		anyGrandchild = bosk.reference(TestEntity.class, Path.of(
			TestEntity.Fields.catalog, "-child-", TestEntity.Fields.catalog, "-grandchild-"));
		anyChildCatalog = bosk.catalogReference(TestEntity.class, Path.of(
			TestEntity.Fields.catalog, "-child-", TestEntity.Fields.catalog));
		anyGrandchildCatalog = bosk.catalogReference(TestEntity.class, Path.of(
			TestEntity.Fields.catalog, "-child-", TestEntity.Fields.catalog, "-grandchild-", TestEntity.Fields.catalog));
		anyListingEntry = bosk.reference(ListingEntry.class, Path.of(
			TestEntity.Fields.listing, "-entity-"));
		anyMappingEntry = bosk.reference(TestEntity.class, Path.of(
			TestEntity.Fields.mapping, "-entity-"));

		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = bosk.readContext()) {
			initialRoot = bosk.rootReference().value();
		}
	}

	@Nonnull
	private TestEntity initialRoot(Bosk<TestEntity> b) throws InvalidTypeException {
		return TestEntity.empty(rootID, b.catalogReference(TestEntity.class, Path.just(TestEntity.Fields.catalog)));
	}

	TestEntity autoInitialize(Reference<TestEntity> ref) {
		if (ref.path().isEmpty()) {
			// Root always exists; nothing to do
			return null;
		} else {
			Reference<TestEntity> outer;
			try {
				outer = ref.enclosingReference(TestEntity.class);
			} catch (InvalidTypeException e) {
				throw new AssertionError("Every entity besides the root should be inside another entity", e);
			}
			autoInitialize(outer);
			TestEntity newEntity = emptyEntityAt(ref);
			driver.submitInitialization(ref, newEntity);
			return newEntity;
		}
	}

	@Nonnull
	private TestEntity emptyEntityAt(Reference<TestEntity> ref) {
		CatalogReference<TestEntity> catalogRef;
		try {
			catalogRef = ref.thenCatalog(TestEntity.class, TestEntity.Fields.catalog);
		} catch (InvalidTypeException e) {
			throw new AssertionError("Every entity should have a catalog in it", e);
		}
		return TestEntity.empty(Identifier.from(ref.path().lastSegment()), catalogRef);
	}

	protected TestEntity newEntity(Identifier id, CatalogReference<TestEntity> enclosingCatalogRef) throws InvalidTypeException {
		return TestEntity.empty(id, enclosingCatalogRef.then(id).thenCatalog(TestEntity.class, TestEntity.Fields.catalog));
	}

	void assertCorrectBoskContents() {
		try {
			driver.flush();
		} catch (InterruptedException e) {
			currentThread().interrupt();
			throw new AssertionError("Unexpected interruption", e);
		}
		TestEntity expected, actual;
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = canonicalBosk.readContext()) {
			expected = canonicalBosk.rootReference().value();
		}
		try (@SuppressWarnings("unused") Bosk<TestEntity>.ReadContext context = bosk.readContext()) {
			actual = bosk.rootReference().value();
		}
		assertEquals(expected, actual);
	}

	@EqualsAndHashCode(callSuper=false) @ToString
	@Accessors(fluent=true) @Getter @With
	@FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	@FieldNameConstants
	public static class TestEntity extends Entity {
		Identifier id;
		String string;
		Catalog<TestEntity> catalog;
		Listing<TestEntity> listing;
		Mapping<TestEntity, TestEntity> mapping;
		Optional<TestValues> values;

		public static TestEntity empty(Identifier id, Reference<Catalog<TestEntity>> catalogRef) {
			return new TestEntity(id,
				id.toString(),
				Catalog.empty(),
				Listing.empty(catalogRef),
				Mapping.empty(catalogRef),
				Optional.empty());
		}

		public TestEntity withChild(TestEntity child) {
			return this.withCatalog(catalog.with(child));
		}
	}

	@EqualsAndHashCode(callSuper=false) @ToString
	@Accessors(fluent=true) @Getter @With
	@FieldDefaults(level=AccessLevel.PRIVATE, makeFinal=true) @RequiredArgsConstructor
	@FieldNameConstants
	public static class TestValues implements ConfigurationNode {
		String string;
		ListValue<String> list;
		MapValue<String> map;

		public static TestValues blank() {
			return new TestValues("", ListValue.empty(), MapValue.empty());
		}
	}
}
