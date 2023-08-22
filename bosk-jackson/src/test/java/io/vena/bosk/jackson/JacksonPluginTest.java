package io.vena.bosk.jackson;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.BindingEnvironment;
import io.vena.bosk.Bosk;
import io.vena.bosk.Bosk.ReadContext;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.Identifier;
import io.vena.bosk.ListValue;
import io.vena.bosk.Listing;
import io.vena.bosk.ListingEntry;
import io.vena.bosk.MapValue;
import io.vena.bosk.Path;
import io.vena.bosk.Reference;
import io.vena.bosk.ReflectiveEntity;
import io.vena.bosk.SerializationPlugin.DeserializationScope;
import io.vena.bosk.SideTable;
import io.vena.bosk.StateTreeNode;
import io.vena.bosk.TestEntityBuilder;
import io.vena.bosk.annotations.DerivedRecord;
import io.vena.bosk.annotations.DeserializationPath;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.exceptions.MalformedPathException;
import io.vena.bosk.exceptions.ParameterUnboundException;
import io.vena.bosk.exceptions.UnexpectedPathException;
import java.lang.reflect.Type;
import java.util.ArrayList;
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
import lombok.experimental.FieldNameConstants;
import lombok.var;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static io.vena.bosk.AbstractBoskTest.TestEnum.OK;
import static io.vena.bosk.ListingEntry.LISTING_ENTRY;
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

class JacksonPluginTest extends AbstractBoskTest {
	private Bosk<TestRoot> bosk;
	private TestEntityBuilder teb;
	private JacksonPlugin jacksonPlugin;
	private ObjectMapper boskMapper;
	private CatalogReference<TestEntity> entitiesRef;
	private Reference<TestEntity> parentRef;

	/**
	 * Not configured by JacksonPlugin. Only for checking the properties of the generated JSON.
	 */
	private ObjectMapper plainMapper;

	@BeforeEach
	void setUpJackson() throws Exception {
		bosk = setUpBosk(Bosk::simpleDriver);
		teb = new TestEntityBuilder(bosk);
		entitiesRef = bosk.rootReference().thenCatalog(TestEntity.class, Path.just(TestRoot.Fields.entities));
		parentRef = entitiesRef.then(Identifier.from("parent"));

		plainMapper = new ObjectMapper()
			.enable(INDENT_OUTPUT);

		jacksonPlugin = new JacksonPlugin();
		boskMapper = new ObjectMapper()
			.registerModule(jacksonPlugin.moduleFor(bosk))
			.enable(INDENT_OUTPUT);
	}

	@Test
	void identifier_works() throws JsonProcessingException {
		Identifier id = Identifier.from("testID");
		String expected = "\"testID\"";
		assertEquals(expected, boskMapper.writeValueAsString(id));
		assertEquals(id, boskMapper.readerFor(Identifier.class).readValue(expected));
	}

	@ParameterizedTest
	@MethodSource("catalogArguments")
	void catalog_works(List<String> ids) {
		// Build entities and put them in a Catalog
		List<TestEntity> entities = new ArrayList<>();
		for (String id : ids) {
			entities.add(teb.blankEntity(Identifier.from(id), OK));
		}
		Catalog<TestEntity> catalog = Catalog.of(entities);

		// Build the expected JSON structure
		List<Map<String, Object>> expected = new ArrayList<>();
		entities.forEach(e1 -> expected.add(singletonMap(e1.id().toString(), plainObjectFor(e1))));

		assertJacksonWorks(expected, catalog, new TypeReference<Catalog<TestEntity>>(){}, Path.just(TestRoot.Fields.entities));
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
	void listing_works(List<String> strings, List<Identifier> ids) {
		Listing<TestEntity> listing = Listing.of(entitiesRef, ids);

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("ids", strings);
		expected.put("domain", entitiesRef.pathString());

		assertJacksonWorks(expected, listing, new TypeReference<Listing<TestEntity>>() {}, Path.just("doesn't matter"));
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
	void listingEntry_works() throws JsonProcessingException {
		assertEquals("true", boskMapper.writeValueAsString(LISTING_ENTRY));
		Assertions.assertEquals(LISTING_ENTRY, boskMapper.readValue("true", ListingEntry.class));
	}

	@ParameterizedTest
	@MethodSource("sideTableArguments")
	void sideTable_works(List<String> keys, Map<String,String> valuesByString, Map<Identifier, String> valuesById) {
		SideTable<TestEntity, String> sideTable = SideTable.fromOrderedMap(entitiesRef, valuesById);

		List<Map<String, Object>> expectedList = new ArrayList<>();
		valuesByString.forEach((key, value) -> expectedList.add(singletonMap(key, value)));

		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("valuesById", expectedList);
		expected.put("domain", entitiesRef.pathString());

		assertJacksonWorks(
			expected,
			sideTable,
			new TypeReference<SideTable<TestEntity, String>>(){},
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
	void phantom_isOmitted() throws InvalidTypeException, JsonProcessingException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(Phantoms.Fields.phantomString)));
	}

	@Test
	void optional_isOmitted() throws InvalidTypeException, JsonProcessingException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(Optionals.Fields.optionalString)));
	}

	@Test
	void optional_isIncluded() throws InvalidTypeException, JsonProcessingException {
		String contents = "OPTIONAL STRING CONTENTS";
		TestEntity entity = makeEntityWithOptionalString(Optional.of(contents));
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, containsString(Optionals.Fields.optionalString));
		assertThat(json, containsString(contents));
	}

	@Test
	void rootReference_works() throws JsonProcessingException {
		String json = boskMapper.writeValueAsString(bosk.rootReference());
		assertEquals("\"/\"", json);
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void listValue_serializationWorks(List<?> list, JavaType type) throws JsonProcessingException {
		ListValue<?> listValue = ListValue.from(list);
		String expected = plainMapper.writeValueAsString(list);
		assertEquals(expected, boskMapper.writeValueAsString(listValue));
	}

	@ParameterizedTest
	@MethodSource("listValueArguments")
	void listValue_deserializationWorks(List<?> list, JavaType type) throws JsonProcessingException {
		ListValue<?> expected = ListValue.from(list);
		String json = plainMapper.writeValueAsString(list);
		Object actual = boskMapper.readerFor(type).readValue(json);
		assertEquals(expected, actual);
		assertTrue(actual instanceof ListValue);
	}

	private static Stream<Arguments> listValueArguments() {
		return Stream.of(
			listValueCase(String.class),
			listValueCase(String.class, "Hello"),
			listValueCase(String.class, "first", "second")
		);
	}

	private static Arguments listValueCase(Type entryType, Object...entries) {
		JavaType entryJavaType = TypeFactory.defaultInstance().constructType(entryType);
		return Arguments.of(asList(entries), TypeFactory.defaultInstance().constructParametricType(ListValue.class, entryJavaType));
	}

	@Test
	void listValue_parameterizedElement_works() {
		var actual = new NodeWithGenerics<>(
			ListValue.of(1, 2),
			ListValue.of(
				new NodeWithGenerics<>(
					ListValue.of(3.0, 4.0),
					ListValue.of("string1, string2")
				)
			)
		);
		LinkedHashMap<Object, Object> expectedB = new LinkedHashMap<>();
		expectedB.put("listOfA", asList(3.0, 4.0));
		expectedB.put("listOfB", asList("string1, string2"));
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("listOfA", asList(1, 2));
		expected.put("listOfB", singletonList(expectedB));

		assertJacksonWorks(
			expected,
			actual,
			new TypeReference<NodeWithGenerics<Integer, NodeWithGenerics<Double, String>>>() {},
			Path.empty());
	}

	@Value
	public static class NodeWithGenerics<A,B> implements StateTreeNode {
		ListValue<A> listOfA;
		ListValue<B> listOfB;
	}

	@ParameterizedTest
	@MethodSource("mapValueArguments")
	void mapValue_serializationWorks(Map<String,?> map, JavaType type) throws JsonProcessingException {
		MapValue<?> mapValue = MapValue.fromOrderedMap(map);
		String expected = plainMapper.writeValueAsString(map);
		assertEquals(expected, boskMapper.writeValueAsString(mapValue));
	}

	@ParameterizedTest
	@MethodSource("mapValueArguments")
	void mapValue_deserializationWorks(Map<String,?> map, JavaType type) throws JsonProcessingException {
		MapValue<?> expected = MapValue.fromOrderedMap(map);
		String json = plainMapper.writeValueAsString(map);
		Object actual = boskMapper.readerFor(type).readValue(json);
		assertEquals(expected, actual);
		assertTrue(actual instanceof MapValue);
	}

	private static Stream<Arguments> mapValueArguments() {
		return Stream.of(
			mapValueCase(String.class),
			mapValueCase(String.class, kv("key1", "Hello")),
			mapValueCase(String.class, kv("first", "firstValue"), kv("second", "secondValue"))
		);
	}

	@SafeVarargs
	private static Arguments mapValueCase(Type entryType, Map<String, Object>...entries) {
		JavaType entryJavaType = TypeFactory.defaultInstance().constructType(entryType);
		Map<String, Object> map = new LinkedHashMap<>();
		for (var entry: entries) {
			map.putAll(entry);
		}
		return Arguments.of(map, TypeFactory.defaultInstance().constructParametricType(MapValue.class, entryJavaType));
	}

	private static Map<String, Object> kv(String key, Object value) {
		return singletonMap(key, value);
	}

	@Test
	void implicitRefs_omitted() throws InvalidTypeException, JsonProcessingException {
		TestEntity entity = makeEntityWithOptionalString(Optional.empty());
		String json = boskMapper.writeValueAsString(entity);
		assertThat(json, not(containsString(ImplicitRefs.Fields.reference)));
		assertThat(json, not(containsString(ImplicitRefs.Fields.enclosingRef)));
	}

	@Test
	void derivedRecord_basic_works() throws InvalidTypeException, JsonProcessingException {
		Reference<ImplicitRefs> iref = parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs);
		ImplicitRefs reflectiveEntity;
		try (ReadContext context = bosk.readContext()) {
			reflectiveEntity = iref.value();
		}

		String expectedJSON = boskMapper.writeValueAsString(new ExpectedBasic(
			iref,
			"stringValue",
			iref,
			"stringValue"
		));
		String actualJSON = boskMapper.writeValueAsString(new ActualBasic(
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
			deserialized = boskMapper.readerFor(ActualBasic.class).readValue(expectedJSON);
		}

		assertEquals(reflectiveEntity, deserialized.entity);
	}

	/**
	 * Should be serialized the same as {@link ActualBasic}.
	 */
	@RequiredArgsConstructor @Getter
	public static class ExpectedBasic implements StateTreeNode {
		final Reference<ImplicitRefs> entity;
		final String nonEntity;
		final Reference<ImplicitRefs> optionalEntity;
		final String optionalNonEntity;
	}

	@RequiredArgsConstructor @Getter
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
	void derivedRecord_list_works() throws InvalidTypeException, JsonProcessingException {
		Reference<ImplicitRefs> iref = parentRef.then(ImplicitRefs.class, TestEntity.Fields.implicitRefs);
		ImplicitRefs reflectiveEntity;
		try (ReadContext context = bosk.readContext()) {
			reflectiveEntity = iref.value();
		}

		String expectedJSON = boskMapper.writeValueAsString(singletonList(iref.path().urlEncoded()));
		String actualJSON = boskMapper.writeValueAsString(new ActualList(reflectiveEntity));

		assertEquals(expectedJSON, actualJSON);

		ActualList deserialized;
		try (ReadContext context = bosk.readContext()) {
			deserialized = boskMapper.readerFor(ActualList.class).readValue(expectedJSON);
		}

		ListValue<ReflectiveEntity<?>> expected = ListValue.of(reflectiveEntity);
		assertEquals(expected, deserialized);
	}

	@Getter
	@DerivedRecord
	private static class ActualList extends ListValue<ReflectiveEntity<?>> {
		protected ActualList(ReflectiveEntity<?>... entries) {
			super(entries);
		}
	}

	@Test
	void deserializationPath_works() throws InvalidTypeException {
		Reference<ImplicitRefs> anyImplicitRefs = bosk.rootReference().then(ImplicitRefs.class, Path.of(TestRoot.Fields.entities, "-entity-", TestEntity.Fields.implicitRefs));
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
		try (DeserializationScope scope = jacksonPlugin.overlayScope(env)) {
			assertJacksonWorks(plainObject, boskObject, new TypeReference<DeserializationPathContainer>() {}, Path.empty());
		}
	}

	@Value
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

	private void assertJacksonWorks(Map<String,?> plainObject, Object boskObject, TypeReference<?> boskObjectTypeRef, Path path) {
		Map<String, Object> actualPlainObject = plainObjectFor(boskObject);
		assertEquals(plainObject, actualPlainObject, "Serialized object should match expected");

		Object deserializedBoskObject = boskObjectFor(plainObject, boskObjectTypeRef, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		Map<String, Object> roundTripPlainObject = plainObjectFor(deserializedBoskObject);
		assertEquals(plainObject, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private void assertJacksonWorks(List<?> plainList, Object boskObject, TypeReference<?> boskObjectTypeRef, Path path) {
		JavaType boskObjectType = TypeFactory.defaultInstance().constructType(boskObjectTypeRef);
		List<Object> actualPlainList = plainListFor(boskObject);
		assertEquals(plainList, actualPlainList, "Serialized object should match expected");

		Object deserializedBoskObject = boskListFor(plainList, boskObjectType, path);
		assertEquals(boskObject, deserializedBoskObject, "Deserialized object should match expected");

		List<Object> roundTripPlainObject = plainListFor(deserializedBoskObject);
		assertEquals(plainList, roundTripPlainObject, "Round-trip serialized object should match expected");

	}

	private Map<String, Object> plainObjectFor(Object boskObject) {
		try {
			JavaType mapJavaType = TypeFactory.defaultInstance().constructParametricType(Map.class, String.class, Object.class);
			String json = boskMapper.writeValueAsString(boskObject);
			return plainMapper.readerFor(mapJavaType).readValue(json);
		} catch (JsonProcessingException e) {
			throw new AssertionError(e);
		}
	}

	private List<Object> plainListFor(Object boskObject) {
		try {
			JavaType listJavaType = TypeFactory.defaultInstance().constructParametricType(List.class, Object.class);
			String json = boskMapper.writeValueAsString(boskObject);
			return plainMapper.readerFor(listJavaType).readValue(json);
		} catch (JsonProcessingException e) {
			throw new AssertionError(e);
		}
	}

	private Object boskObjectFor(Map<String, ?> plainObject, TypeReference<?> boskObjectTypeRef, Path path) {
		try {
			JavaType boskJavaType = TypeFactory.defaultInstance().constructType(boskObjectTypeRef);
			JavaType mapJavaType = TypeFactory.defaultInstance().constructParametricType(Map.class, String.class, Object.class);
			String json = plainMapper.writerFor(mapJavaType).writeValueAsString(plainObject);
			try (DeserializationScope scope = jacksonPlugin.newDeserializationScope(path)) {
				return boskMapper.readerFor(boskJavaType).readValue(json);
			}
		} catch (JsonProcessingException e) {
			throw new AssertionError(e);
		}
	}

	private Object boskListFor(List<?> plainList, JavaType boskListType, Path path) {
		try {
			JavaType boskJavaType = TypeFactory.defaultInstance().constructType(boskListType);
			JavaType listJavaType = TypeFactory.defaultInstance().constructParametricType(List.class, Object.class);
			String json = plainMapper.writerFor(listJavaType).writeValueAsString(plainList);
			try (DeserializationScope scope = jacksonPlugin.newDeserializationScope(path)) {
				return boskMapper.readerFor(boskJavaType).readValue(json);
			}
		} catch (JsonProcessingException e) {
			throw new AssertionError(e);
		}
	}

	// Sad paths

	@Test
	void nonexistentPath_throws() {
		assertThrows(UnexpectedPathException.class, () ->
			boskMapper
				.readerFor(TypeFactory.defaultInstance().constructParametricType(Reference.class, String.class))
				.readValue("\"/some/nonexistent/path\""));
	}

	@Test
	void catalogFromEmptyMap_throws() {
		assertJsonException("{}", Catalog.class, TestEntity.class);
	}

	@Test
	void catalogWithContentsArray_throws() {
		assertJsonException("{ \"contents\": [] }", Catalog.class, TestEntity.class);
	}

	@Test
	void listingWithoutDomain_throws() {
		assertJsonException("{ \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithoutIDs_throws() {
		assertJsonException("{ \"domain\": \"/entities\" }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithExtraneousField_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"extraneous\": 0, \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithTwoDomains_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void listingWithTwoIDsFields_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"ids\": [], \"ids\": [] }", Listing.class, TestEntity.class);
	}

	@Test
	void sideTableWithNoDomain_throws() {
		assertJsonException("{ \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithNoValues_throws() {
		assertJsonException("{ \"domain\": \"/entities\" }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithExtraneousField_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"extraneous\": 0 }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithTwoDomains_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"domain\": \"/entities\", \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithValuesMap_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": {} }", SideTable.class, TestEntity.class, String.class);
	}

	@Test
	void sideTableWithTwoValuesFields_throws() {
		assertJsonException("{ \"domain\": \"/entities\", \"valuesById\": [], \"valuesById\": [] }", SideTable.class, TestEntity.class, String.class);
	}

	private void assertJsonException(String json, Class<?> rawClass, Type... parameters) {
		JavaType[] params = new JavaType[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			params[i] = TypeFactory.defaultInstance().constructType(parameters[i]);
		}
		JavaType parametricType = TypeFactory.defaultInstance().constructParametricType(rawClass, params);
		assertThrows(JsonParseException.class, () -> boskMapper.readerFor(parametricType).readValue(json));
	}

	@Test
	void deserializationPath_wrongType_throws() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskMapper.readerFor(WrongType.class).readValue("{ \"notAString\": { \"id\": \"123\" } }");
		});
	}

	@Value
	public static class WrongType implements StateTreeNode {
		@DeserializationPath("/entities/123/string")
		ImplicitRefs notAString;
	}

	@Test
	void deserializationPath_parameterUnbound_throws() {
		assertThrows(ParameterUnboundException.class, () -> {
			boskMapper.readerFor(EntityParameter.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	@Value
	public static class EntityParameter implements StateTreeNode {
		@DeserializationPath("/entities/-entity-")
		ImplicitRefs field;
	}

	@Test
	void deserializationPath_malformedPath() {
		assertThrows(MalformedPathException.class, () -> {
			boskMapper.readerFor(MalformedPath.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	@Value
	public static class MalformedPath implements StateTreeNode {
		@DeserializationPath("/malformed////path")
		ImplicitRefs field;
	}

	@Test
	void deserializationPath_nonexistentPath_throws() {
		assertThrows(UnexpectedPathException.class, () -> {
			boskMapper.readerFor(NonexistentPath.class).readValue("{ \"field\": { \"id\": \"123\" } }");
		});
	}

	@Value
	public static class NonexistentPath implements StateTreeNode {
		@DeserializationPath("/nonexistent/path")
		ImplicitRefs field;
	}
}
