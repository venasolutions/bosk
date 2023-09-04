package io.vena.bosk.drivers.mongo;

import io.vena.bosk.AbstractBoskTest;
import io.vena.bosk.Bosk;
import io.vena.bosk.Catalog;
import io.vena.bosk.CatalogReference;
import io.vena.bosk.Identifier;
import io.vena.bosk.Reference;
import io.vena.bosk.TestEntityBuilder;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.util.Types;
import java.io.IOException;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static io.vena.bosk.TypeValidation.validateType;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatterTest extends AbstractBoskTest {
	Bosk<TestRoot> bosk;
	CatalogReference<TestEntity> entitiesRef;
	Reference<TestEntity> weirdRef;
	static final String WEIRD_ID = "weird|i.d.";
	Formatter formatter;
	private TestEntity weirdEntity;

	@BeforeEach
	void setupFormatter() throws InvalidTypeException, IOException, InterruptedException {
		bosk = setUpBosk(Bosk::simpleDriver);
		TestEntityBuilder builder = new TestEntityBuilder(bosk);
		entitiesRef = builder.entitiesRef();
		weirdRef = builder.entityRef(Identifier.from(WEIRD_ID));
		weirdEntity = builder.blankEntity(Identifier.from(WEIRD_ID), TestEnum.OK);
		bosk.driver().submitReplacement(entitiesRef, Catalog.of(weirdEntity));
		bosk.driver().flush();
		formatter = new Formatter(bosk, new BsonPlugin());
	}

	@Test
	void object2bsonValue() {
		BsonValue actual = formatter.object2bsonValue(Catalog.of(weirdEntity), Types.parameterizedType(Catalog.class, TestEntity.class));
		BsonValue weirdDoc = new BsonDocument()
			.append("id", new BsonString(WEIRD_ID))
			.append("string", new BsonString(WEIRD_ID))
			.append("testEnum", new BsonString("OK"))
			.append("children", new BsonDocument())
			.append("oddChildren", new BsonDocument()
				.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
				.append("ids", new BsonDocument())
			)
			.append("stringSideTable", new BsonDocument()
				.append("domain", new BsonString("/entities/weird%7Ci.d./children"))
				.append("valuesById", new BsonDocument())
			)
			.append("phantoms", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_phantoms"))
			)
			.append("optionals", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_optionals"))
			)
			.append("implicitRefs", new BsonDocument()
				.append("id", new BsonString(WEIRD_ID + "_implicitRefs"))
			)
			;

		ArrayList<String> dottedName = Formatter.dottedFieldNameSegments(weirdRef, bosk.rootReference());
		BsonDocument expected = new BsonDocument()
			.append(dottedName.get(dottedName.size()-1), weirdDoc);
		assertEquals(expected, actual);
	}

	@ParameterizedTest
	@MethodSource("dottedNameCases")
	void dottedFieldNameSegment(String plain, String dotted) {
		assertEquals(dotted, Formatter.dottedFieldNameSegment(plain));
	}

	@ParameterizedTest
	@MethodSource("dottedNameCases")
	void undottedFieldNameSegment(String plain, String dotted) {
		assertEquals(plain, Formatter.undottedFieldNameSegment(dotted));
	}

	static Stream<Arguments> dottedNameCases() {
		return Stream.of(
			dottedNameCase("%", "%25"),
			dottedNameCase("$", "%24"),
			dottedNameCase(".", "%2E"),
			dottedNameCase("\0", "%00"),
			dottedNameCase("|", "%7C"),
			dottedNameCase("!", "%21"),
			dottedNameCase("~", "%7E"),
			dottedNameCase("[", "%5B"),
			dottedNameCase("]", "%5D"),
			dottedNameCase("+", "%2B"),
			dottedNameCase(" ", "%20")
		);
	}

	static Arguments dottedNameCase(String plain, String dotted) {
		return Arguments.of(plain, dotted);
	}

	@Test
	void manifest_passesTypeValidation() throws InvalidTypeException {
		validateType(Manifest.class);
	}
}
