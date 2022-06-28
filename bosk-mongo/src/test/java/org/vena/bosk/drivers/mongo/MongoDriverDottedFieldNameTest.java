package org.vena.bosk.drivers.mongo;

import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.vena.bosk.Bosk;
import org.vena.bosk.CatalogReference;
import org.vena.bosk.Identifier;
import org.vena.bosk.Path;
import org.vena.bosk.Reference;
import org.vena.bosk.drivers.AbstractDriverTest;
import org.vena.bosk.drivers.state.TestEntity;
import org.vena.bosk.exceptions.InvalidTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MongoDriverDottedFieldNameTest extends AbstractDriverTest {
	private Bosk<TestEntity> bosk;

	@BeforeEach
	void setUpStuff() {
		bosk = new Bosk<TestEntity>("Test bosk", TestEntity.class, this::initialRoot, Bosk::simpleDriver);
	}

	private TestEntity initialRoot(Bosk<TestEntity> testEntityBosk) throws InvalidTypeException {
		return TestEntity.empty(Identifier.from("root"), rootCatalogRef(testEntityBosk));
	}

	private CatalogReference<TestEntity> rootCatalogRef(Bosk<TestEntity> bosk) throws InvalidTypeException {
		return bosk.catalogReference(TestEntity.class, Path.just( TestEntity.Fields.catalog));
	}

	static class PathArgumentProvider implements ArgumentsProvider {

		@Override
		public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
			final String base = "state";
			return Stream.of(
					args("/", base),
					args("/catalog", base + ".catalog"),
					args("/listing", base + ".listing"),
					args("/sideTable", base + ".sideTable"),
					args("/catalog/xyz", base + ".catalog.xyz"),
					args("/listing/xyz", base + ".listing.ids.xyz"),
					args("/sideTable/xyz", base + ".sideTable.valuesById.xyz")
					);
		}

		private Arguments args(String boskPath, String dottedFieldName) {
			return Arguments.of(boskPath, dottedFieldName);
		}
	}

	@ParameterizedTest
	@ArgumentsSource(PathArgumentProvider.class)
	void testDottedFieldNameOf(String boskPath, String dottedFieldName) throws InvalidTypeException {
		Reference<?> reference = bosk.reference(Object.class, Path.parse(boskPath));
		String actual = Formatter.dottedFieldNameOf(reference, bosk.rootReference());
		assertEquals(dottedFieldName, actual);
		//assertThrows(AssertionError.class, ()-> MongoDriver.dottedFieldNameOf(reference, catalogReference.then(Identifier.from("whoopsie"))));
	}

	@ParameterizedTest
	@ArgumentsSource(PathArgumentProvider.class)
	void testReferenceTo(String boskPath, String dottedFieldName) throws InvalidTypeException {
		Reference<?> expected = bosk.reference(Object.class, Path.parse(boskPath));
		Reference<?> actual = Formatter.referenceTo(dottedFieldName, bosk.rootReference());
		assertEquals(expected, actual);
		assertEquals(expected.path(), actual.path());
		assertEquals(expected.targetType(), actual.targetType());
	}

}
