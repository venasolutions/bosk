package org.vena.bosk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.vena.bosk.Bosk.ReadContext;
import org.vena.bosk.SerializationPlugin.DeserializationScope;
import org.vena.bosk.annotations.DerivedRecord;
import org.vena.bosk.annotations.DeserializationPath;
import org.vena.bosk.exceptions.InvalidTypeException;
import org.vena.bosk.exceptions.MalformedPathException;
import org.vena.bosk.exceptions.ParameterUnboundException;
import org.vena.bosk.exceptions.UnexpectedPathException;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static org.vena.bosk.ListingEntry.LISTING_ENTRY;
import static org.vena.bosk.util.Types.parameterizedType;

class GsonPluginTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestEntityBuilder teb;
	private GsonPlugin gsonPlugin;
	private Gson boskGson;
	private CatalogReference<TestEntity> entitiesRef;
	private Reference<TestEntity> parentRef;

	/**
	 * Not configured by GsonPlugin. Only for checking the properties of the generated JSON.
	 */
	private Gson plainGson;

	@BeforeEach
	void setUpGson() throws Exception {
		bosk = setUpBosk(gsonRoundTripFactory(GsonBuilder::setPrettyPrinting));
		teb = new TestEntityBuilder(bosk);
		entitiesRef = bosk.catalogReference(TestEntity.class, Path.just(TestRoot.Fields.entities));
		parentRef = entitiesRef.then(Identifier.from("parent"));

		plainGson = new GsonBuilder()
				.setPrettyPrinting()
				.create();

		gsonPlugin = new GsonPlugin();
		TypeAdapterFactory typeAdapterFactory = gsonPlugin.adaptersFor(bosk);
		boskGson = new GsonBuilder()
			.registerTypeAdapterFactory(typeAdapterFactory)
			.excludeFieldsWithoutExposeAnnotation()
			.setPrettyPrinting()
			.create();
	}

	@ParameterizedTest
	@MethodSource("catalogArguments")
	void testToJson_catalog(List<String> ids) {
		// Build entities and put them in a Catalog
		List<TestEntity> entities = new ArrayList<>();
		for (String id : ids) {
			entities.add(teb.blankEntity(Identifier.from(id), OK));
		}
		Catalog<TestEntity> catalog = Catalog.of(entities);

		// Build the expected JSON structure
		Map<String, Object> contents = new LinkedHashMap<>();
		entities.forEach(e1 -> contents.put(e1.id().toString(), plainObjectFor(e1, e1.getClass())));
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("contents", contents);
		expected.put("order", ids);

		Map<String, Object> actual = assertGsonWorks(expected, catalog, new TypeToken<Catalog<TestEntity>>(){}.getType(), Path.just(TestRoot.Fields.entities));
		assertMapKeyOrder(ids, actual.get("contents"));
	}

	static Stream<Arguments> catalogArguments() {
		return Stream.of(
				catalogCase(),
				catalogCase("1", "3", "2")
		);
	}

	private static Arguments catalogCase(String ...ids) {
		return Arguments.of(asList(ids));
	}

	@Test
	void testCatalogOrder_noOrderField() {
		List<Identifier> expectedIDs = Stream.of("b", "o", "s", "k").map(Identifier::from).collect(toList());
		String json = plainGson.toJson(plainCatalogWithEntities(expectedIDs), parameterizedType(Map.class, String.class, Object.class));
		Catalog<TestEntity> actual;
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(Path.just(TestRoot.Fields.entities))) {
			actual = boskGson.fromJson(json, parameterizedType(Catalog.class, TestEntity.class));
		}
		assertEquals(expectedIDs, actual.ids());
	}

	@Test
	void testCatalogOrder_overridingOrderField() {
		List<String> expectedIDs = asList("b", "o", "s", "k");
		List<Identifier> idsInWrongOrder = Stream.of("s", "k", "b", "o").map(Identifier::from).collect(toList());
		Map<String, Object> map = plainCatalogWithEntities(idsInWrongOrder);
		map.put("order", expectedIDs);

		String json = plainGson.toJson(map, parameterizedType(Map.class, String.class, Object.class));
		Catalog<TestEntity> actual;
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(Path.just(TestRoot.Fields.entities))) {
			actual = boskGson.fromJson(json, parameterizedType(Catalog.class, TestEntity.class));
		}
		assertEquals(expectedIDs.stream().map(Identifier::from).collect(toList()), actual.ids());
	}

	private Map<String, Object> plainCatalogWithEntities(List<Identifier> expectedIDs) {
		Map<String, Object> expectedContents = new LinkedHashMap<>();
		for (Identifier id : expectedIDs) {
			expectedContents.put(id.toString(), plainObjectFor(teb.blankEntity(id, OK), TestEntity.class));
		}
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("contents", expectedContents);
		return expected;
	}

	@ParameterizedTest
	@MethodSource("listingArguments")
	void testToJson_listing(List<String> strings, List<Identifier> ids) {
		Listing<TestEntity> listing = Listing.of(entitiesRef, ids);

		Map<String, Object> expected = new HashMap<>();
		expected.put("catalog", entitiesRef.pathString());
		expected.put("ids", strings);

		assertGsonWorks(expected, listing, new TypeToken<Listing<TestEntity>>(){}.getType(), Path.just("doesn't matter"));
	}

	static Stream<Arguments> listingArguments() {
		return Stream.of(
			listingCase(),
			listingCase("1", "3", "2")
		);
	}

	private static Arguments listingCase(String ...strings) {
		return Arguments.of(asList(strings), Stream.of(strings).map(Identifier::from).collect(toList()));
	}

	@Test
	void testListingEntry() {
		assertEquals("true", boskGson.toJson(LISTING_ENTRY));
		assertEquals(LISTING_ENTRY, boskGson.fromJson("true", ListingEntry.class));
	}

	@ParameterizedTest
	@MethodSource("mappingArguments")
	void testToJson_mapping(List<String> keys, Map<String,String> valuesByString, Map<Identifier, String> valuesById) {
		Mapping<TestEntity, String> mapping = Mapping.fromOrderedMap(entitiesRef, valuesById);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("catalog", entitiesRef.pathString());
		expected.put("order", keys);
		expected.put("valuesById", valuesByString);

		Map<String, Object> actual = assertGsonWorks(
			expected,
			mapping,
			new TypeToken<Mapping<TestEntity, String>>(){}.getType(),
			Path.just("doesn't matter")
		);

		List<String> expectedKeys = valuesById.keySet().stream().map(Identifier::toString).collect(toList());
		assertMapKeyOrder(expectedKeys, actual.get("valuesById"));
	}

	static Stream<Arguments> mappingArguments() {
		return Stream.of(
				mappingCase(f->{}),
				mappingCase(f->{
					f.accept("1", "First");
					f.accept("3", "Second");
					f.accept("2", "Third");
				})
		);
	}

	static <V> Arguments mappingCase(Consumer<BiConsumer<String,V>> initializer) {
		Map<String,V> valuesByString = new LinkedHashMap<>();
		initializer.accept(valuesByString::put);

		Map<Identifier, V> valuesById = new LinkedHashMap<>();
		initializer.accept((k,v) -> valuesById.put(Identifier.from(k), v));

		List<String> keys = new ArrayList<>();
		initializer.accept((k,v)-> keys.add(k));
		return Arguments.of(keys, valuesByString, valuesById);
	}

	@Test
	void testMappingOrder_noOrderField() {
		List<String> expectedIDs = asList("b","o","s","k");
		String json = plainGson.toJson(plainMapping(expectedIDs), parameterizedType(Map.class, String.class, Object.class));
		Mapping<TestEntity, String> actual;
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(Path.just("doesn't matter"))) {
			actual = boskGson.fromJson(json, parameterizedType(Mapping.class, TestEntity.class, String.class));
		}
		assertEquals(expectedIDs.stream().map(Identifier::from).collect(toList()), actual.ids());
	}

	@Test
	void testMappingOrder_overridingOrderField() {
		List<String> expectedIDs = asList("b", "o", "s", "k");
		Map<String, Object> map = plainMapping(asList("s", "k", "b", "o"));
		map.put("order", expectedIDs);

		String json = plainGson.toJson(map, parameterizedType(Map.class, String.class, Object.class));
		Mapping<TestEntity, String> actual;
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(Path.just(TestRoot.Fields.entities))) {
			actual = boskGson.fromJson(json, parameterizedType(Mapping.class, TestEntity.class, String.class));
		}
		assertEquals(expectedIDs.stream().map(Identifier::from).collect(toList()), actual.ids());
	}

	private Map<String, Object> plainMapping(List<String> ids) {
		Map<String, Object> valuesById = new LinkedHashMap<>();
		ids.forEach(id -> valuesById.put(id, id));
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("catalog", TestRoot.Fields.entities);
		result.put("valuesById", valuesById);
		return result;
	}

	@Test
	void testOptionalIsOmitted() throws InvalidTypeException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskGson.toJson(entity);
		assertThat(json, not(containsString(Optionals.Fields.optionalString)));
	}

	@Test
	void testOptionalIsIncluded() throws InvalidTypeException {
		String contents = "OPTIONAL STRING CONTENTS";
		TestEntity entity = makeEntityWithOptionalString(Optional.of(contents));
		String json = boskGson.toJson(entity);
		assertThat(json, containsString(Optionals.Fields.optionalString));
		assertThat(json, containsString(contents));
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void testToJson_listValue(List<?> list, TypeToken<?> typeToken) {
		ListValue<?> listValue = ListValue.from(list);
		String expected = plainGson.toJson(list);
		assertEquals(expected, boskGson.toJson(listValue, typeToken.getType()));
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void testFromJson_listValue(List<?> list, TypeToken<?> typeToken) {
		ListValue<?> expected = ListValue.from(list);
		String json = plainGson.toJson(list);
		Object actual = boskGson.fromJson(json, typeToken.getType());
		assertEquals(expected, actual);
		assertTrue(actual instanceof ListValue);
	}

	private static Stream<Arguments> listValueArguments() {
		return Stream.of(
			listValueCase(String.class),
			listValueCase(String.class, "Hello"),
			listValueCase(String.class, "first", "second")
			/*
			TODO: We can't yet handle parameterized node types!
			Can't tell that inside NodeWithGenerics<Double, Integer> the field listOfA has type ListValue<Double>.
			We currently don't do parameter substitution on type variables.

			listValueCase(
				parameterizedType(NodeWithGenerics.class, Double.class, Integer.class),
				new NodeWithGenerics<>(ListValue.of(1.0, 2.0), ListValue.of(3, 4)))
			 */
		);
	}

	private static Arguments listValueCase(Type entryType, Object...entries) {
		return Arguments.of(asList(entries), TypeToken.getParameterized(ListValue.class, entryType));
	}

	/**
	 * Exercise the type-parameter handling a bit
	 */
	@Value @Accessors(fluent = true)
	private static class NodeWithGenerics<A,B> implements ConfigurationNode {
		ListValue<A> listOfA;
		ListValue<B> listOfB;
	}

	@Test
	void testImplicitsAreOmitted() throws InvalidTypeException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskGson.toJson(entity);
		assertThat(json, not(containsString(ImplicitRefs.Fields.reference)));
		assertThat(json, not(containsString(ImplicitRefs.Fields.enclosingRef)));
	}

	@Test
	void testBasicDerivedRecord() throws InvalidTypeException {
		Reference<ImplicitRefs> iref = parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs);
		ImplicitRefs reflectiveEntity;
		try (ReadContext context = bosk.readContext()) {
			reflectiveEntity = iref.value();
		}

		String expectedJSON = boskGson.toJson(new ExpectedBasic(iref, "stringValue"));
		String actualJSON = boskGson.toJson(new ActualBasic(reflectiveEntity, "stringValue"));

		assertEquals(expectedJSON, actualJSON);

		ActualBasic deserialized;
		try (ReadContext context = bosk.readContext()) {
			deserialized = boskGson.fromJson(expectedJSON, ActualBasic.class);
		}

		assertEquals(reflectiveEntity, deserialized.entity);
	}

	/**
	 * Should be serialized the same as {@link ActualBasic}.
	 */
	@RequiredArgsConstructor @Getter @Accessors(fluent = true)
	public static class ExpectedBasic implements ConfigurationNode {
		final Reference<ImplicitRefs> entity;
		final String nonEntity;
	}

	@RequiredArgsConstructor @Getter @Accessors(fluent = true)
	@DerivedRecord
	public static class ActualBasic {
		final ImplicitRefs entity;
		final String nonEntity;
	}

	@Test
	void testDerivedRecordList() throws InvalidTypeException {
		Reference<ImplicitRefs> iref = parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs);
		ImplicitRefs reflectiveEntity;
		try (ReadContext context = bosk.readContext()) {
			reflectiveEntity = iref.value();
		}

		String expectedJSON = boskGson.toJson(singletonList(iref.path().urlEncoded()));
		String actualJSON = boskGson.toJson(new ActualList(reflectiveEntity));

		assertEquals(expectedJSON, actualJSON);

		ActualList deserialized;
		try (ReadContext context = bosk.readContext()) {
			deserialized = boskGson.fromJson(expectedJSON, ActualList.class);
		}

		ListValue<ReflectiveEntity<?>> expected = ListValue.of(reflectiveEntity);
		assertEquals(expected, deserialized);
	}

	@Getter @Accessors(fluent = true)
	@DerivedRecord
	private static class ActualList extends ListValue<ReflectiveEntity<?>> {
		protected ActualList(ReflectiveEntity<?>... entries) {
			super(entries);
		}
	}

	@Test
	void testDeserializationPath() throws InvalidTypeException {
		Reference<ImplicitRefs> anyImplicitRefs = bosk.reference(ImplicitRefs.class, Path.of(TestRoot.Fields.entities, "-entity-", TestEntity.Fields.implicitRefs));
		Reference<ImplicitRefs> ref1 = anyImplicitRefs.boundTo(Identifier.from("123"));
		ImplicitRefs firstObject = new ImplicitRefs(
			Identifier.from("firstObject"),
			ref1, ref1.enclosingReference(TestEntity.class),
			ref1, ref1.enclosingReference(TestEntity.class)
			);
		Reference<ImplicitRefs> ref2 = anyImplicitRefs.boundTo(Identifier.from("456"));
		ImplicitRefs secondObject = new ImplicitRefs(
			Identifier.from("secondObject"),
			ref2, ref2.enclosingReference(TestEntity.class),
			ref2, ref2.enclosingReference(TestEntity.class)
		);

		DeserializationPathContainer boskObject = new DeserializationPathContainer(firstObject, secondObject);

		Map<String, Object> plainObject = new LinkedHashMap<>();
		plainObject.put(DeserializationPathContainer.Fields.firstField, singletonMap("id", firstObject.id().toString()));
		plainObject.put(DeserializationPathContainer.Fields.secondField, singletonMap("id", secondObject.id().toString()));

		BindingEnvironment env = BindingEnvironment.empty().builder()
			.bind("entity1", Identifier.from("123"))
			.bind("entity2", Identifier.from("456"))
			.build();
		try (DeserializationScope scope = gsonPlugin.overlayScope(env)) {
			assertGsonWorks(plainObject, boskObject, DeserializationPathContainer.class, Path.empty());
		}
	}

	@Value
	@Accessors(fluent = true)
	@FieldNameConstants
	public static class DeserializationPathContainer implements ConfigurationNode {
		@DeserializationPath("entities/-entity1-/implicitRefs")
		ImplicitRefs firstField;

		@DeserializationPath("entities/-entity2-/implicitRefs")
		ImplicitRefs secondField;
	}

	private TestEntity makeEntityWithOptionalString(Optional<String> optionalString) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = entitiesRef;
		Identifier entityID = Identifier.unique("testOptional");
		Reference<TestEntity> entityRef = catalogRef.then(entityID);
		CatalogReference<TestChild> childrenRef = entityRef.thenCatalog(TestChild.class, TestEntity.Fields.children);
		Reference<ImplicitRefs> implicitRefsRef = entityRef.then(ImplicitRefs.class, "implicitRefs");
		return new TestEntity(entityID, entityID.toString(), OK, Catalog.empty(), Listing.empty(childrenRef), Mapping.empty(childrenRef),
				new Optionals(Identifier.unique("optionals"), optionalString, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
				new ImplicitRefs(Identifier.unique("implicitRefs"), implicitRefsRef, entityRef, implicitRefsRef, entityRef));
	}

	private Map<String, Object> assertGsonWorks(Map<String,?> plainObject, Object boskObject, Type boskObjectType, Path path) {
		Map<String, Object> actualPlainObject = plainObjectFor(boskObject, boskObjectType);
		assertEquals(plainObject, actualPlainObject, "Serialized object should match expected");

		Object deserializedBoskObject = bsonObjectFor(plainObject, boskObjectType, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		Map<String, Object> roundTripPlainObject = plainObjectFor(deserializedBoskObject, boskObjectType);
		assertEquals(plainObject, roundTripPlainObject, "Round-trip serialized object should match expected");

		return actualPlainObject;
	}

	private Map<String, Object> plainObjectFor(Object bsonObject, Type bsonObjectType) {
		String json = boskGson.toJson(bsonObject, bsonObjectType);
		return plainGson.fromJson(json, parameterizedType(Map.class, String.class, Object.class));
	}

	private Object bsonObjectFor(Map<String, ?> plainObject, Type bsonObjectType, Path path) {
		String json = plainGson.toJson(plainObject, new TypeToken<Map<String, Object>>(){}.getType());
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(path)) {
			return boskGson.fromJson(json, bsonObjectType);
		}
	}

	private void assertMapKeyOrder(List<String> expectedKeys, Object map) {
		@SuppressWarnings("unchecked")
		Map<String, Object> actualContents = (Map<String, Object>) map;
		assertEquals(expectedKeys, new ArrayList<>(actualContents.keySet()), "Order of keys must be preserved by serialization");
	}

	// Sad paths

	@Test
	void testBadJson_badReference() {
		assertThrows(UnexpectedPathException.class, () ->
			boskGson.fromJson("\"some/nonexistent/path\"", parameterizedType(Reference.class, String.class)));
	}

	@Test
	void testBadJson_catalogWithNoContents() {
		assertJsonException("{ \"order\": [] }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_catalogWithTwoContents() {
		assertJsonException("{ \"contents\": {}, \"contents\": {} }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_catalogWithTwoOrders() {
		assertJsonException("{ \"order\": {}, \"order\": {} }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_catalogWithContentsArray() {
		assertJsonException("{ \"contents\": [] }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_catalogWithMismatchedContentsAndOrder() {
		assertJsonException("{ \"order\": [\"a\"], \"contents\": { } }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithNoCatalog() {
		assertJsonException("{ \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithNoIds() {
		assertJsonException("{ \"catalog\": \"entities\" }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithExtraneousField() {
		assertJsonException("{ \"catalog\": \"entities\", \"extraneous\": 0, \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithTwoCatalogs() {
		assertJsonException("{ \"catalog\": \"entities\", \"catalog\": \"entities\", \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithTwoIdsFields() {
		assertJsonException("{ \"catalog\": \"entities\", \"ids\": [], \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_mappingWithNoCatalog() {
		assertJsonException("{ \"valuesById\": {} }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithNoValues() {
		assertJsonException("{ \"catalog\": \"entities\" }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithExtraneousField() {
		assertJsonException("{ \"catalog\": \"entities\", \"valuesById\": {}, \"extraneous\": 0 }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithTwoCatalogs() {
		assertJsonException("{ \"catalog\": \"entities\", \"catalog\": \"entities\", \"valuesById\": {} }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithValuesList() {
		assertJsonException("{ \"catalog\": \"entities\", \"valuesById\": [] }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithTwoValuesFields() {
		assertJsonException("{ \"catalog\": \"entities\", \"valuesById\": {}, \"valuesById\": {} }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithTwoOrderFields() {
		assertJsonException("{ \"catalog\": \"entities\", \"order\": [], \"order\": [], \"valuesById\": {} }", Mapping.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_mappingWithMismatchedValuesAndOrder() {
		assertJsonException("{ \"catalog\": \"entities\", \"valuesById\": {}, \"order\": [\"a\"] }", Mapping.class, TestEntity.class, String.class);
	}

	private void assertJsonException(String json, Class<?> rawClass, Type... parameters) {
		assertThrows(JsonParseException.class, () -> boskGson.fromJson(json, parameterizedType(rawClass, parameters)));
	}

	@Test
	void testBadDeserializationPath_wrongType() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskGson.fromJson("{ \"notAString\": { \"id\": \"123\" } }", WrongType.class);
		});
	}

	@Value
	@Accessors(fluent = true)
	public static class WrongType implements ConfigurationNode {
		@DeserializationPath("entities/123/string")
		ImplicitRefs notAString;
	}

	@Test
	void testBadDeserializationPath_parameterUnbound() {
		assertThrows(ParameterUnboundException.class, () -> {
			boskGson.fromJson("{ \"field\": { \"id\": \"123\" } }", EntityParameter.class);
		});
	}

	@Value
	@Accessors(fluent = true)
	public static class EntityParameter implements ConfigurationNode {
		@DeserializationPath("entities/-entity-")
		ImplicitRefs field;
	}

	@Test
	void testBadDeserializationPath_malformedPath() {
		assertThrows(MalformedPathException.class, () -> {
			boskGson.fromJson("{ \"field\": { \"id\": \"123\" } }", MalformedPath.class);
		});
	}

	@Value
	@Accessors(fluent = true)
	public static class MalformedPath implements ConfigurationNode {
		@DeserializationPath("malformed////path")
		ImplicitRefs field;
	}

	@Test
	void testBadDeserializationPath_nonexistentPath() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskGson.fromJson("{ \"field\": { \"id\": \"123\" } }", NonexistentPath.class);
		});
	}

	@Value
	@Accessors(fluent = true)
	public static class NonexistentPath implements ConfigurationNode {
		@DeserializationPath("nonexistent/path")
		ImplicitRefs field;
	}
}
