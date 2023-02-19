package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.EnumerableByIdentifier;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.SideTableReference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.AbstractDriverTest;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.var;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BsonSurgeonTest extends AbstractDriverTest {
	BsonSurgeon surgeon;
	BsonPlugin bsonPlugin;
	Formatter formatter;
	private List<Reference<? extends EnumerableByIdentifier<?>>> separateCollections;

	Refs refs;

	public interface Refs {
		@ReferencePath("/catalog") CatalogReference<TestEntity> catalog();
		@ReferencePath("/catalog/-entity-") Reference<TestEntity> entity(Identifier entity);
		@ReferencePath("/sideTable") SideTableReference<TestEntity, TestEntity> sideTable();
		@ReferencePath("/catalog/-entity-/catalog") CatalogReference<TestEntity> nestedCatalog();
	}

	@BeforeEach
	void setup() throws InvalidTypeException {
		setupBosksAndReferences(Bosk::simpleDriver);
		bsonPlugin = new BsonPlugin();
		formatter = new Formatter(bosk, bsonPlugin);

		refs = bosk.buildReferences(Refs.class);

		CatalogReference<TestEntity> catalogRef = refs.catalog();
		SideTableReference<TestEntity, TestEntity> sideTableRef = refs.sideTable();
		CatalogReference<TestEntity> nestedCatalogRef = refs.nestedCatalog();
		separateCollections = asList(
			catalogRef,
			sideTableRef,
			nestedCatalogRef
		);
		makeCatalog(catalogRef);
		makeCatalog(nestedCatalogRef.boundTo(Identifier.from("entity1")));
		makeCatalog(nestedCatalogRef.boundTo(Identifier.from("weird|i.d. +")));
		driver.submitReplacement(sideTableRef.then(Identifier.from("child1")), TestEntity.empty(Identifier.from("sideTableValue"), catalogRef));
		surgeon = new BsonSurgeon(separateCollections);
	}

	@Test
	void root_roundTripWorks() {
		doTest(bosk.rootReference());
	}

	@Test
	void catalogEntry_roundTripWorks() {
		doTest(refs.entity(Identifier.from("entity1")));
	}

	@Test
	void sideTable_roundTripWorks() {
		doTest(refs.sideTable());
	}

	@Test
	void root_partForEachEntry() {
		Reference<TestEntity> rootRef = bosk.rootReference();
		BsonDocument entireDoc;
		try (var __ = bosk.readContext()) {
			entireDoc = (BsonDocument) formatter.object2bsonValue(rootRef.value(), rootRef.targetType());
		}

		List<BsonDocument> parts = surgeon.scatter(bosk.rootReference(), rootRef, entireDoc.clone());
		List<String> partPaths = parts.stream()
			.map(part -> part.getString("_id"))
			.map(BsonString::getValue)
			.collect(toList());
		Set<String> actual = new LinkedHashSet<>(partPaths);
		assertEquals(partPaths.size(), actual.size(), "partPaths should have no duplicates");

		Set<String> expected = new LinkedHashSet<>(asList(
			"|catalog|entity1|catalog|child1",
			"|catalog|entity1|catalog|child2",
			"|catalog|weird%7Ci%2Ed%2E%20%2B|catalog|child1",
			"|catalog|weird%7Ci%2Ed%2E%20%2B|catalog|child2",
			"|catalog|child1",
			"|catalog|child2",
			"|catalog|entity1",
			"|catalog|weird%7Ci%2Ed%2E%20%2B",
			"|sideTable|valuesById|child1",
			"|"
		));

		assertEquals(expected, actual);
	}

	@Test
	void manuallyConstructed_works() {
		BsonDocument actual = surgeon.gather(asList(
			new BsonDocument()
				.append("_id", new BsonString("|catalog|entry1"))
				.append("state", new BsonDocument()),
			new BsonDocument()
				.append("_id", new BsonString("|"))
				.append("state", new BsonDocument()
					.append("_id", new BsonString("rootID"))
					.append("catalog", new BsonDocument("entry1", BsonBoolean.TRUE))
				))
		);
		BsonDocument expected = new BsonDocument()
			.append("_id", new BsonString("rootID"))
			.append("catalog", new BsonDocument()
				.append("entry1", new BsonDocument()));
		assertEquals(expected, actual);
	}

	@Test
	void duplicatePaths_throws() {
		assertThrows(IllegalArgumentException.class, () -> {
			surgeon.gather(asList(
				new BsonDocument()
					.append("_id", new BsonString("|catalog|entry1"))
					.append("state", new BsonDocument()),
				new BsonDocument()
					.append("_id", new BsonString("|catalog|entry1"))
					.append("state", new BsonDocument()),
				new BsonDocument()
					.append("_id", new BsonString("|"))
					.append("state", new BsonDocument()
						.append("_id", new BsonString("rootID"))
						.append("catalog", new BsonDocument("entry1", BsonBoolean.TRUE))
					))
			);
		});
	}

	private void doTest(Reference<?> mainRef) {
		BsonDocument entireDoc;
		try (var __ = bosk.readContext()) {
			entireDoc = (BsonDocument) formatter.object2bsonValue(mainRef.value(), mainRef.targetType());
		}

		List<BsonDocument> parts = surgeon.scatter(bosk.rootReference(), mainRef, entireDoc.clone());

		JsonWriterSettings jsonWriterSettings = JsonWriterSettings.builder().indent(true).build();
		System.out.println("== Parts ==");
		parts.forEach(part ->
			System.out.println(part.toJson(jsonWriterSettings)));

		List<BsonDocument> receivedParts = parts.stream()
			.map(part -> Document.parse(part.toJson()).toBsonDocument(BsonDocument.class, formatter.codecRegistry()))
			.collect(toList());
		BsonDocument gathered = surgeon.gather(receivedParts);

		assertEquals(entireDoc, gathered);

		System.out.println("== Gathered ==");
		System.out.println(gathered.toJson(jsonWriterSettings));
	}

	private void makeCatalog(CatalogReference<TestEntity> ref) {
		TestEntity child1 = autoInitialize(ref.then(child1ID));
		TestEntity child2 = autoInitialize(ref.then(child2ID));

		Catalog<TestEntity> bothChildren = Catalog.of(child1, child2);
		driver.submitReplacement(ref, bothChildren);
	}
}
