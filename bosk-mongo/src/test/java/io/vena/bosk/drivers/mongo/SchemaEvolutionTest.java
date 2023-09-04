package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.exceptions.InvalidTypeException;
import io.vena.bosk.junit.ParametersByName;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.var;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static org.junit.jupiter.api.Assertions.assertEquals;

@UsesMongoService
@RequiredArgsConstructor(onConstructor = @__({@ParametersByName}))
public class SchemaEvolutionTest {

	private final Helper fromHelper;
	private final Helper toHelper;

	@BeforeAll
	static void beforeAll() {
		AbstractMongoDriverTest.setupMongoConnection();
	}

	@BeforeEach
	void beforeEach(TestInfo testInfo) {
		fromHelper.setupDriverFactory();
		toHelper  .setupDriverFactory();

		fromHelper.clearTearDown(testInfo);
		toHelper  .clearTearDown(testInfo);
	}

	@AfterEach
	void afterEach(TestInfo testInfo) {
		fromHelper.runTearDown(testInfo);
		toHelper  .runTearDown(testInfo);
	}

	@SuppressWarnings("unused")
	static Stream<Helper> fromHelper() {
		return Stream.of(
			new Helper(SEQUOIA, ManifestMode.USE_IF_EXISTS),
			new Helper(SEQUOIA, ManifestMode.CREATE_IF_ABSENT),
			new Helper(PandoFormat.oneBigDocument(), ManifestMode.CREATE_IF_ABSENT)
		);
	}

	@SuppressWarnings("unused")
	static Stream<Helper> toHelper() {
		return fromHelper();
	}

	@ParametersByName
	void pairwiseImplementationKinds_compatible() throws InvalidTypeException {
		LOGGER.debug("Create fromBosk [{}]", fromHelper.name);
		Bosk<TestEntity> fromBosk = newBosk(fromHelper);
		Refs fromRefs = fromBosk.buildReferences(Refs.class);

		LOGGER.debug("Set distinctive string");
		fromBosk.driver().submitReplacement(fromRefs.string(), "Distinctive String");

		LOGGER.debug("Create toBosk [{}]", toHelper.name);
		Bosk<TestEntity> toBosk = newBosk(toHelper);
		Refs toRefs = toBosk.buildReferences(Refs.class);

		LOGGER.debug("Perform read");
		try (var __ = toBosk.readContext()) {
			assertEquals("Distinctive String", toRefs.string().value());
		}
	}

	private static Bosk<TestEntity> newBosk(Helper helper) {
		return new Bosk<TestEntity>(helper.name, TestEntity.class, helper::initialRoot, helper.driverFactory);
	}

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(DatabaseFormat preferredFormat, ManifestMode manifestMode) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName())
				.preferredDatabaseFormat(preferredFormat)
				.experimental(Experimental.builder()
					.manifestMode(manifestMode)
					.build())
			);
			this.name = (preferredFormat + ":" + manifestMode).toLowerCase();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public interface Refs {
		@ReferencePath("/string") Reference<String> string();
	}

	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaEvolutionTest.class);
}
