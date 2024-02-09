package io.vena.bosk.drivers.mongo;

import io.vena.bosk.Bosk;
import io.vena.bosk.Reference;
import io.vena.bosk.annotations.ReferencePath;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.Experimental;
import io.vena.bosk.drivers.mongo.MongoDriverSettings.ManifestMode;
import io.vena.bosk.drivers.state.TestEntity;
import io.vena.bosk.junit.ParametersByName;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.vena.bosk.drivers.mongo.MongoDriverSettings.DatabaseFormat.SEQUOIA;
import static org.junit.jupiter.api.Assertions.assertEquals;

@UsesMongoService
public class SchemaEvolutionTest {

	private final Helper fromHelper;
	private final Helper toHelper;

	@ParametersByName
	SchemaEvolutionTest(Configuration fromConfig, Configuration toConfig) {
		int dbCounter = DB_COUNTER.incrementAndGet();
		this.fromHelper = new Helper(fromConfig, dbCounter);
		this.toHelper = new Helper(toConfig, dbCounter);
	}

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
	static Stream<Configuration> fromConfig() {
		return Stream.of(
			new Configuration(SEQUOIA, ManifestMode.USE_IF_EXISTS),
			new Configuration(SEQUOIA, ManifestMode.CREATE_IF_ABSENT),
			new Configuration(PandoFormat.oneBigDocument(), ManifestMode.CREATE_IF_ABSENT),
			new Configuration(PandoFormat.withGraftPoints("/catalog", "/sideTable"), ManifestMode.CREATE_IF_ABSENT)
		);
	}

	@SuppressWarnings("unused")
	static Stream<Configuration> toConfig() {
		return fromConfig();
	}

	@ParametersByName
	void pairwise_readCompatible() throws Exception {
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

	record Configuration(
		DatabaseFormat preferredFormat,
		ManifestMode manifestMode
	) {
		@Override
		public String toString() {
			return preferredFormat + "&" + manifestMode;
		}
	}

	static final class Helper extends AbstractMongoDriverTest {
		final String name;

		public Helper(Configuration config, int dbCounter) {
			super(MongoDriverSettings.builder()
				.database(SchemaEvolutionTest.class.getSimpleName() + "_" + dbCounter)
				.preferredDatabaseFormat(config.preferredFormat())
				.experimental(Experimental.builder()
					.manifestMode(config.manifestMode())
					.build()));
			this.name = (config.preferredFormat() + ":" + config.manifestMode()).toLowerCase();
		}

		@Override
		public String toString() {
			return name;
		}
	}

	public interface Refs {
		@ReferencePath("/string")
		Reference<String> string();
	}

	private static final AtomicInteger DB_COUNTER = new AtomicInteger(0);
	private static final Logger LOGGER = LoggerFactory.getLogger(SchemaEvolutionTest.class);
}
