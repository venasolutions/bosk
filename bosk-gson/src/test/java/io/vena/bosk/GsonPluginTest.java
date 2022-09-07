package io.vena.bosk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import io.vena.bosk.Bosk.ReadContext;
import io.vena.bosk.SerializationPlugin.DeserializationScope;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.annotations.DeserializationPath;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.MalformedPathException;
import io.vena.bosk.exceptions.ParameterUnboundException;
import io.vena.bosk.exceptions.UnexpectedPathException;
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

import static io.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
import static io.vena.bosk.util.Types.parameterizedType;
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
		bosk = setUpBosk(Bosk::simpleDriver);
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
		List<Map<String, Object>> expected = new ArrayList<>();
		entities.forEach(e1 -> expected.add(singletonMap(e1.id().toString(), plainObjectFor(e1, e1.getClass()))));

		assertGsonWorks(expected, catalog, new TypeToken<Catalog<TestEntity>>(){}.getType(), Path.just(TestRoot.Fields.entities));
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

	@ParameterizedTest
	@MethodSource("listingArguments")
	void testToJson_listing(List<String> strings, List<Identifier> ids) {
		Listing<TestEntity> listing = Listing.of(entitiesRef, ids);

		Map<String, Object> expected = new HashMap<>();
		expected.put("domain", entitiesRef.pathString());
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
	@MethodSource("sideTableArguments")
	void testToJson_sideTable(List<String> keys, Map<String,String> valuesByString, Map<Identifier, String> valuesById) {
		SideTable<TestEntity, String> sideTable = SideTable.fromOrderedMap(entitiesRef, valuesById);

		List<Map<String, Object>> expectedList = new ArrayList<>();
		valuesByString.forEach((key, value) -> expectedList.add(singletonMap(key, value)));

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("domain", entitiesRef.pathString());
		expected.put("valuesById", expectedList);

		assertGsonWorks(
			expected,
			sideTable,
			new TypeToken<SideTable<TestEntity, String>>(){}.getType(),
			Path.just("doesn't matter")
		);
	}

	static Stream<Arguments> sideTableArguments() {
		return Stream.of(
				sideTableCase(f->{}),
				sideTableCase(f->{
					f.accept("1", "First");
					f.accept("3", "Second");
					f.accept("2", "Third");
				})
		);
	}

	static <V> Arguments sideTableCase(Consumer<BiConsumer<String,V>> initializer) {
		Map<String,V> valuesByString = new LinkedHashMap<>();
		initializer.accept(valuesByString::put);

		Map<Identifier, V> valuesById = new LinkedHashMap<>();
		initializer.accept((k,v) -> valuesById.put(Identifier.from(k), v));

		List<String> keys = new ArrayList<>();
		initializer.accept((k,v)-> keys.add(k));
		return Arguments.of(keys, valuesByString, valuesById);
	}

	@Test
	void testPhantomIsOmitted() throws InvalidTypeException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskGson.toJson(entity);
		assertThat(json, not(containsString(Phantoms.Fields.phantomString)));
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

	@Test
	void testRootReference() {
		String json = boskGson.toJson(bosk.rootReference());
		assertEquals("\"/\"", json);
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
	private static class NodeWithGenerics<A,B> implements StateTreeNode {
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

		String expectedJSON = boskGson.toJson(new ExpectedBasic(
			iref,
			"stringValue",
			iref,
			"stringValue"
		));
		String actualJSON = boskGson.toJson(new ActualBasic(
			reflectiveEntity,
			"stringValue",
			Optional.of(reflectiveEntity),
			Optional.of("stringValue"),
			Optional.empty(),
			Optional.empty()
		));

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
	public static class ExpectedBasic implements StateTreeNode {
		final Reference<ImplicitRefs> entity;
		final String nonEntity;
		final Reference<ImplicitRefs> optionalEntity;
		final String optionalNonEntity;
	}

	@RequiredArgsConstructor @Getter @Accessors(fluent = true)
	@DerivedRecord
	public static class ActualBasic {
		final ImplicitRefs entity;
		final String nonEntity;
		final Optional<ImplicitRefs> optionalEntity;
		final Optional<String> optionalNonEntity;
		final Optional<ImplicitRefs> emptyEntity;
		final Optional<String> emptyNonEntity;
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
	public static class DeserializationPathContainer implements StateTreeNode {
		@DeserializationPath("/entities/-entity1-/implicitRefs")
		ImplicitRefs firstField;

		@DeserializationPath("/entities/-entity2-/implicitRefs")
		ImplicitRefs secondField;
	}

	private TestEntity makeEntityWithOptionalString(Optional<String> optionalString) throws InvalidTypeException {
		CatalogReference<TestEntity> catalogRef = entitiesRef;
		Identifier entityID = Identifier.unique("testOptional");
		Reference<TestEntity> entityRef = catalogRef.then(entityID);
		CatalogReference<TestChild> childrenRef = entityRef.thenCatalog(TestChild.class, TestEntity.Fields.children);
		Reference<ImplicitRefs> implicitRefsRef = entityRef.then(ImplicitRefs.class, "implicitRefs");
		return new TestEntity(entityID, entityID.toString(), OK, Catalog.empty(), Listing.empty(childrenRef), SideTable.empty(childrenRef),
				Phantoms.empty(Identifier.unique("phantoms")),
				new Optionals(Identifier.unique("optionals"), optionalString, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
				new ImplicitRefs(Identifier.unique("implicitRefs"), implicitRefsRef, entityRef, implicitRefsRef, entityRef));
	}

	private void assertGsonWorks(Map<String,?> plainObject, Object boskObject, Type boskObjectType, Path path) {
		Map<String, Object> actualPlainObject = plainObjectFor(boskObject, boskObjectType);
		assertEquals(plainObject, actualPlainObject, "Serialized object should match expected");

		Object deserializedBoskObject = boskGsonObjectFor(plainObject, boskObjectType, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		Map<String, Object> roundTripPlainObject = plainObjectFor(deserializedBoskObject, boskObjectType);
		assertEquals(plainObject, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private void assertGsonWorks(List<?> plainList, Object boskObject, Type boskObjectType, Path path) {
		List<Object> actualPlainList = plainListFor(boskObject, boskObjectType);
		assertEquals(plainList, actualPlainList, "Serialized object should match expected");

		Object deserializedBoskObject = boskGsonListFor(plainList, boskObjectType, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		List<Object> roundTripPlainObject = plainListFor(deserializedBoskObject, boskObjectType);
		assertEquals(plainList, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private Map<String, Object> plainObjectFor(Object bsonObject, Type bsonObjectType) {
		String json = boskGson.toJson(bsonObject, bsonObjectType);
		return plainGson.fromJson(json, parameterizedType(Map.class, String.class, Object.class));
	}

	private List<Object> plainListFor(Object bsonObject, Type bsonObjectType) {
		String json = boskGson.toJson(bsonObject, bsonObjectType);
		return plainGson.fromJson(json, parameterizedType(List.class, Object.class));
	}

	private Object boskGsonObjectFor(Map<String, ?> plainObject, Type bsonObjectType, Path path) {
		String json = plainGson.toJson(plainObject, new TypeToken<Map<String, Object>>(){}.getType());
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(path)) {
			return boskGson.fromJson(json, bsonObjectType);
		}
	}

	private Object boskGsonListFor(List<?> plainList, Type bsonListType, Path path) {
		String json = plainGson.toJson(plainList, new TypeToken<List<Object>>(){}.getType());
		try (DeserializationScope scope = gsonPlugin.newDeserializationScope(path)) {
			return boskGson.fromJson(json, bsonListType);
		}
	}

	// Sad paths

	@Test
	void testBadJson_badReference() {
		assertThrows(UnexpectedPathException.class, () ->
			boskGson.fromJson("\"/some/nonexistent/path\"", parameterizedType(Reference.class, String.class)));
	}

	@Test
	void testBadJson_catalogFromEmptyMap() {
		assertJsonException("{}", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_catalogWithContentsArray() {
		assertJsonException("{ \"contents\": [] }", Catalog.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithNoCatalog() {
		assertJsonException("{ \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithNoIds() {
		assertJsonException("{ \"domain\": \"/entities\" }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithExtraneousField() {
		assertJsonException("{ \"domain\": \"/entities\", \"extraneous\": 0, \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithTwoDomains() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_listingWithTwoIdsFields() {
		assertJsonException("{ \"domain\": \"/entities\", \"ids\": [], \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void testBadJson_sideTableWithNoDomain() {
		assertJsonException("{ \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_sideTableWithNoValues() {
		assertJsonException("{ \"domain\": \"/entities\" }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_sideTableWithExtraneousField() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"extraneous\": 0 }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_sideTableWithTwoDomains() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_sideTableWithValuesMap() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": {} }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void testBadJson_sideTableWithTwoValuesFields() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
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
	public static class WrongType implements StateTreeNode {
		@DeserializationPath("/entities/123/string")
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
	public static class EntityParameter implements StateTreeNode {
		@DeserializationPath("/entities/-entity-")
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
	public static class MalformedPath implements StateTreeNode {
		@DeserializationPath("/malformed////path")
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
	public static class NonexistentPath implements StateTreeNode {
		@DeserializationPath("/nonexistent/path")
		ImplicitRefs field;
	}
}
