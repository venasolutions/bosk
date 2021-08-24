package org.vena.bosk.util;

import org.junit.jupiter.api.Test;
import org.vena.bosk.Catalog;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Entity;
import org.vena.bosk.ListValue;
import org.vena.bosk.Listing;
import org.vena.bosk.ListingReference;
import org.vena.bosk.Mapping;
import org.vena.bosk.MappingReference;
import org.vena.bosk.Reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.vena.bosk.util.Classes.catalog;
import static org.vena.bosk.util.Classes.catalogReference;
import static org.vena.bosk.util.Classes.listValue;
import static org.vena.bosk.util.Classes.listing;
import static org.vena.bosk.util.Classes.listingReference;
import static org.vena.bosk.util.Classes.mapping;
import static org.vena.bosk.util.Classes.mappingReference;
import static org.vena.bosk.util.Classes.reference;

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
	void testMapping() {
		Class<Mapping<Entity, String>> mappingClass = mapping(Entity.class, String.class);
		assertEquals(Mapping.class, mappingClass);
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
	void testMappingReference() {
		Class<MappingReference<Entity, String>> mappingReferenceClass = mappingReference(Entity.class, String.class);
		assertEquals(MappingReference.class, mappingReferenceClass);
	}

	@Test
	void testListValue() {
		Class<ListValue<String>> listValueClass = listValue(String.class);
		assertEquals(ListValue.class, listValueClass);
	}

}
