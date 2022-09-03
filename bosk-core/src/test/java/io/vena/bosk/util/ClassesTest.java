package io.vena.bosk.util;

import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.Entity;
import io.vena.bosk.ListValue;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingReference;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTable;
import io.vena.bosk.SideTableReference;
import org.junit.jupiter.api.Test;

import static io.vena.bosk.util.Classes.catalog;
import static io.vena.bosk.util.Classes.catalogReference;
import static io.vena.bosk.util.Classes.listValue;
import static io.vena.bosk.util.Classes.listing;
import static io.vena.bosk.util.Classes.listingReference;
import static io.vena.bosk.util.Classes.reference;
import static io.vena.bosk.util.Classes.sideTable;
import static io.vena.bosk.util.Classes.sideTableReference;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassesTest {

	@Test
	void testCatalog() {
		Class<Catalog<Entity>> catalogClass = catalog(Entity.class);
		assertEquals(Catalog.class, catalogClass);
	}

	@Test
	void testListing() {
		Class<Listing<Entity>> listingClass = listing(Entity.class);
		assertEquals(Listing.class, listingClass);
	}

	@Test
	void testSideTable() {
		Class<SideTable<Entity, String>> sideTableClass = sideTable(Entity.class, String.class);
		assertEquals(SideTable.class, sideTableClass);
	}

	@Test
	void testReference() {
		Class<Reference<String>> reference = reference(String.class);
		assertEquals(Reference.class, reference);
	}

	@Test
	void testCatalogReference() {
		Class<CatalogReference<Entity>> catalogReferenceClass = catalogReference(Entity.class);
		assertEquals(CatalogReference.class, catalogReferenceClass);
	}

	@Test
	void testListingReference() {
		Class<ListingReference<Entity>> listingReferenceClass = listingReference(Entity.class);
		assertEquals(ListingReference.class, listingReferenceClass);
	}

	@Test
	void testSideTableReference() {
		Class<SideTableReference<Entity, String>> sideTableReferenceClass = sideTableReference(Entity.class, String.class);
		assertEquals(SideTableReference.class, sideTableReferenceClass);
	}

	@Test
	void testListValue() {
		Class<ListValue<String>> listValueClass = listValue(String.class);
		assertEquals(ListValue.class, listValueClass);
	}

}
